import { createServer, type IncomingMessage, type Server as HttpServer } from "node:http";
import { Buffer } from "node:buffer";
import { createClient, type RedisClientType } from "redis";
import { Server as SocketServer, type Socket } from "socket.io";

import { BackendClient } from "./backend-client.js";
import type { ChatConfig } from "./config.js";
import { CHAT_CHANNEL_PATTERN, parseEnvelope, positiveId, record, type ValidEnvelope } from "./validation.js";

const SOCKET_PATH = "/socket.io";
const ROOM_PREFIX = "chat:";
const CREDENTIAL_FIELDS = new Set(["token", "accesstoken", "authorization", "jwt"]);

export interface Logger {
  info(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

const consoleLogger: Logger = {
  info: (message) => console.info(message),
  warn: (message) => console.warn(message),
  error: (message) => console.error(message),
};

type Phase = "pending" | "authenticating" | "authenticated" | "closed";

interface ConnectionState {
  phase: Phase;
  authTimer: NodeJS.Timeout;
  accessToken: string | undefined;
  userId: number | undefined;
  readonly subscriptions: Map<number, { readonly generation: number; phase: "pending" | "joined" }>;
  nextGeneration: number;
  deliveryTail: Promise<void>;
}

type SubscribeAck = (result: { readonly ok: true; readonly chatRoomId: number }
  | { readonly ok: false; readonly chatRoomId: number; readonly code: "FORBIDDEN" | "PENDING" }) => void;

function roomName(chatRoomId: number): string {
  return `${ROOM_PREFIX}${chatRoomId}`;
}

function errorName(error: unknown): string {
  return error instanceof Error && error.name ? error.name : "UnknownError";
}

function hasCredentialQuery(request: IncomingMessage): boolean {
  try {
    const url = new URL(request.url ?? "/", "http://socket.local");
    return [...url.searchParams.keys()]
      .some((key) => CREDENTIAL_FIELDS.has(key.toLowerCase()));
  } catch {
    return true;
  }
}

function hasHandshakeCredentials(socket: Socket): boolean {
  const auth = socket.handshake.auth;
  const authFields = record(auth) ? Object.keys(auth) : [];
  const hasAuthField = authFields.some((key) => CREDENTIAL_FIELDS.has(key.toLowerCase()));
  const authorization = socket.handshake.headers.authorization;
  return hasAuthField || (typeof authorization === "string" && authorization.trim() !== "");
}

function accessToken(payload: unknown, maximumLength: number): string | null {
  if (!record(payload)) return null;
  const hasCanonical = Object.hasOwn(payload, "token");
  const hasAlias = Object.hasOwn(payload, "accessToken");
  if (hasCanonical === hasAlias) return null;
  const token = hasCanonical ? payload.token : payload.accessToken;
  if (typeof token !== "string" || token.length === 0 || token.length > maximumLength
      || token.trim() !== token || token.includes("\r") || token.includes("\n")) return null;
  return token;
}

function chatRoomId(payload: unknown): number | null {
  if (!record(payload) || !positiveId(payload.chatRoomId)) return null;
  return payload.chatRoomId;
}

class BoundedGate {
  private active = 0;
  private closed = false;
  private readonly queued: Array<() => Promise<void>> = [];

  constructor(private readonly concurrency: number, private readonly queueLimit: number,
    private readonly logger: Logger) {}

  schedule(task: () => Promise<void>): boolean {
    if (this.closed) return false;
    if (this.active < this.concurrency) {
      this.run(task);
      return true;
    }
    if (this.queued.length >= this.queueLimit) return false;
    this.queued.push(task);
    return true;
  }

  close(): void {
    this.closed = true;
    this.queued.length = 0;
  }

  private run(task: () => Promise<void>): void {
    this.active += 1;
    void task().catch((error: unknown) => {
      this.logger.warn(`Delivery authorization failed safely (${errorName(error)})`);
    }).finally(() => {
      this.active -= 1;
      const next = this.closed ? undefined : this.queued.shift();
      if (next) this.run(next);
    });
  }
}

export interface ChatServerAddress {
  readonly host: string;
  readonly port: number;
  readonly url: string;
}

export class ChatGateway {
  private readonly backend: BackendClient;
  private readonly httpServer: HttpServer;
  private readonly io: SocketServer;
  private readonly subscriber: RedisClientType;
  private readonly healthRedis: RedisClientType;
  private readonly states = new Map<string, ConnectionState>();
  private readonly deliveryGate: BoundedGate;
  private started = false;
  private stopping = false;
  private addressValue?: ChatServerAddress;

  constructor(private readonly config: ChatConfig, private readonly logger: Logger = consoleLogger) {
    this.backend = new BackendClient(config);
    this.deliveryGate = new BoundedGate(config.deliveryAuthConcurrency,
      config.deliveryAuthQueueLimit, logger);
    this.subscriber = createClient({ url: config.redisUrl });
    this.healthRedis = createClient({ url: config.redisUrl });
    this.subscriber.on("error", (error: unknown) => {
      if (!this.stopping) logger.warn(`Redis subscriber unavailable (${errorName(error)})`);
    });
    this.healthRedis.on("error", (error: unknown) => {
      if (!this.stopping) logger.warn(`Redis health connection unavailable (${errorName(error)})`);
    });

    this.httpServer = createServer((request, response) => {
      let pathname: string;
      try {
        pathname = new URL(request.url ?? "/", "http://health.local").pathname;
      } catch {
        response.writeHead(400, {
          "cache-control": "no-store",
          "content-type": "application/json; charset=utf-8",
        });
        response.end('{"code":"INVALID_REQUEST"}');
        return;
      }
      if (request.method === "GET" && pathname === "/healthz") {
        const ready = this.started && !this.stopping
          && this.subscriber.isReady && this.healthRedis.isReady;
        response.writeHead(ready ? 200 : 503, {
          "cache-control": "no-store",
          "content-type": "application/json; charset=utf-8",
        });
        response.end(JSON.stringify({ status: ready ? "ok" : "unavailable" }));
        return;
      }
      response.writeHead(404, {
        "cache-control": "no-store",
        "content-type": "application/json; charset=utf-8",
      });
      response.end('{"code":"NOT_FOUND"}');
    });

    const origins = [...config.allowedOrigins];
    this.io = new SocketServer(this.httpServer, {
      path: SOCKET_PATH,
      maxHttpBufferSize: config.maxClientPayloadBytes,
      serveClient: false,
      cors: {
        origin: origins,
        methods: ["GET", "POST"],
        credentials: false,
      },
      allowRequest: (request, callback) => {
        if (hasCredentialQuery(request)) {
          callback("credentials must use the authenticate event", false);
          return;
        }
        const origin = request.headers.origin;
        if (typeof origin === "string" && !config.allowedOrigins.has(origin)) {
          callback("origin not allowed", false);
          return;
        }
        callback(null, true);
      },
    });
    this.io.use((socket, next) => {
      if (hasHandshakeCredentials(socket)) {
        next(new Error("credentials must use the authenticate event"));
        return;
      }
      next();
    });
    this.io.on("connection", (socket) => this.connected(socket));
  }

  get address(): ChatServerAddress {
    if (!this.addressValue) throw new Error("Chat server is not listening");
    return this.addressValue;
  }

  async start(portOverride: number = this.config.port): Promise<ChatServerAddress> {
    if (this.started) return this.address;
    if (!Number.isInteger(portOverride) || portOverride < 0 || portOverride > 65_535) {
      throw new Error("Listen port must be between 0 and 65535");
    }
    try {
      await Promise.all([this.subscriber.connect(), this.healthRedis.connect()]);
      await this.subscriber.pSubscribe(CHAT_CHANNEL_PATTERN, (message, channel) => {
        this.redisEvent(channel, message);
      });
      await new Promise<void>((resolve, reject) => {
        const onError = (error: Error): void => reject(error);
        this.httpServer.once("error", onError);
        this.httpServer.listen(portOverride, this.config.host, () => {
          this.httpServer.off("error", onError);
          resolve();
        });
      });
      const address = this.httpServer.address();
      if (!address || typeof address === "string") throw new Error("Unable to determine listen address");
      const publicHost = address.address === "::" || address.address === "0.0.0.0"
        ? "127.0.0.1" : address.address;
      this.addressValue = {
        host: address.address,
        port: address.port,
        url: `http://${publicHost.includes(":") ? `[${publicHost}]` : publicHost}:${address.port}`,
      };
      this.started = true;
      return this.addressValue;
    } catch (error) {
      await this.forceClose();
      throw error;
    }
  }

  async stop(): Promise<void> {
    if (this.stopping) return;
    this.stopping = true;
    this.started = false;
    this.deliveryGate.close();
    for (const state of this.states.values()) clearTimeout(state.authTimer);
    this.states.clear();
    this.io.disconnectSockets(true);

    let timeout: NodeJS.Timeout | undefined;
    const deadline = new Promise<void>((resolve) => {
      timeout = setTimeout(resolve, this.config.shutdownTimeoutMs);
      timeout.unref();
    });
    const graceful = (async () => {
      if (this.subscriber.isOpen) {
        try { await this.subscriber.pUnsubscribe(CHAT_CHANNEL_PATTERN); } catch { /* force close below */ }
      }
      await new Promise<void>((resolve) => this.io.close(() => resolve()));
      await Promise.allSettled([this.closeRedis(this.subscriber), this.closeRedis(this.healthRedis)]);
    })();
    await Promise.race([graceful, deadline]);
    if (timeout) clearTimeout(timeout);
    await this.forceClose();
  }

  async close(): Promise<void> {
    await this.stop();
  }

  private connected(socket: Socket): void {
    const authTimer = setTimeout(() => {
      const state = this.states.get(socket.id);
      if (state && state.phase !== "authenticated") {
        this.authenticationFailed(socket, state, "TIMEOUT");
      }
    }, this.config.authTimeoutMs);
    authTimer.unref();
    const state: ConnectionState = {
      phase: "pending",
      authTimer,
      accessToken: undefined,
      userId: undefined,
      subscriptions: new Map(),
      nextGeneration: 0,
      deliveryTail: Promise.resolve(),
    };
    this.states.set(socket.id, state);

    socket.on("authenticate", (payload: unknown) => {
      void this.authenticate(socket, state, payload);
    });
    socket.on("subscribe", (payload: unknown, acknowledge?: unknown) => {
      void this.subscribe(socket, state, payload,
        typeof acknowledge === "function" ? acknowledge as SubscribeAck : undefined);
    });
    socket.on("unsubscribe", (payload: unknown) => {
      void this.unsubscribe(socket, state, payload);
    });
    socket.on("disconnect", () => {
      clearTimeout(state.authTimer);
      state.phase = "closed";
      state.accessToken = undefined;
      this.states.delete(socket.id);
    });
  }

  private async authenticate(socket: Socket, state: ConnectionState, payload: unknown): Promise<void> {
    const token = accessToken(payload, this.config.maxTokenLength);
    if (state.phase !== "pending" || token === null) {
      if (state.phase !== "authenticated" && state.phase !== "closed") {
        this.authenticationFailed(socket, state, "INVALID");
      }
      return;
    }
    state.phase = "authenticating";
    const result = await this.backend.session(token);
    if (!socket.connected || this.states.get(socket.id) !== state || state.phase !== "authenticating") return;
    if (!result.ok) {
      this.authenticationFailed(socket, state,
        result.failure === "unavailable" ? "UNAVAILABLE" : "INVALID");
      return;
    }
    clearTimeout(state.authTimer);
    state.phase = "authenticated";
    state.accessToken = token;
    state.userId = result.value.userId;
    socket.emit("authenticated");
  }

  private async subscribe(socket: Socket, state: ConnectionState, payload: unknown,
    acknowledge?: SubscribeAck): Promise<void> {
    if (state.phase !== "authenticated" || !state.accessToken || !state.userId) return;
    const roomId = chatRoomId(payload);
    if (roomId === null) return;
    const existing = state.subscriptions.get(roomId);
    if (existing?.phase === "pending") {
      acknowledge?.({ ok: false, chatRoomId: roomId, code: "PENDING" });
      return;
    }
    if (existing?.phase === "joined") {
      acknowledge?.({ ok: true, chatRoomId: roomId });
      return;
    }
    if (state.subscriptions.size >= this.config.maxSubscriptionsPerSocket) {
      socket.emit("forbidden", { chatRoomId: roomId });
      acknowledge?.({ ok: false, chatRoomId: roomId, code: "FORBIDDEN" });
      return;
    }

    const generation = state.nextGeneration + 1;
    state.nextGeneration = generation;
    state.subscriptions.set(roomId, { generation, phase: "pending" });
    const token = state.accessToken;
    const userId = state.userId;
    const result = await this.backend.subscription(token, roomId);
    const currentSubscription = state.subscriptions.get(roomId);
    if (!this.current(socket, state, token, userId)
        || currentSubscription?.generation !== generation
        || currentSubscription.phase !== "pending") return;
    if (!result.ok) {
      state.subscriptions.delete(roomId);
      if (result.failure === "unauthorized") {
        this.authenticationFailed(socket, state, "INVALID");
      } else {
        await this.revokeRoom(socket, state, roomId);
        socket.emit("forbidden", { chatRoomId: roomId });
        acknowledge?.({ ok: false, chatRoomId: roomId, code: "FORBIDDEN" });
      }
      return;
    }
    if (result.value.userId !== userId) {
      this.authenticationFailed(socket, state, "INVALID");
      return;
    }
    await socket.join(roomName(roomId));
    const joinedSubscription = state.subscriptions.get(roomId);
    if (!this.current(socket, state, token, userId)
        || joinedSubscription?.generation !== generation
        || joinedSubscription.phase !== "pending") {
      await socket.leave(roomName(roomId));
      return;
    }
    joinedSubscription.phase = "joined";
    acknowledge?.({ ok: true, chatRoomId: roomId });
  }

  private async unsubscribe(socket: Socket, state: ConnectionState, payload: unknown): Promise<void> {
    if (state.phase !== "authenticated") return;
    const roomId = chatRoomId(payload);
    if (roomId === null) return;
    if (!state.subscriptions.delete(roomId)) return;
    await socket.leave(roomName(roomId));
  }

  private redisEvent(channel: string, raw: string): void {
    if (this.stopping) return;
    const envelope = parseEnvelope(channel, raw, this.config.maxRedisPayloadBytes);
    if (!envelope) return;
    const socketIds = this.io.sockets.adapter.rooms.get(roomName(envelope.chatRoomId));
    if (!socketIds) return;
    for (const socketId of [...socketIds]) {
      const socket = this.io.sockets.sockets.get(socketId);
      const state = this.states.get(socketId);
      if (!socket || !state) continue;
      this.deliveryGate.schedule(() => this.orderedDeliver(socket, state, envelope));
    }
  }

  private async orderedDeliver(socket: Socket, state: ConnectionState,
    envelope: ValidEnvelope): Promise<void> {
    const delivery = state.deliveryTail.then(() => this.deliver(socket, state, envelope));
    state.deliveryTail = delivery.catch(() => {});
    await delivery;
  }

  private async deliver(socket: Socket, state: ConnectionState, envelope: ValidEnvelope): Promise<void> {
    if (state.phase !== "authenticated" || !state.accessToken || !state.userId
        || state.subscriptions.get(envelope.chatRoomId)?.phase !== "joined") return;
    const token = state.accessToken;
    const userId = state.userId;
    const result = await this.backend.subscription(token, envelope.chatRoomId);
    if (!this.current(socket, state, token, userId)
        || state.subscriptions.get(envelope.chatRoomId)?.phase !== "joined") return;
    if (!result.ok) {
      if (result.failure === "unauthorized") {
        this.authenticationFailed(socket, state, "INVALID");
      } else if (result.failure === "forbidden") {
        await this.revokeRoom(socket, state, envelope.chatRoomId);
        socket.emit("forbidden", { chatRoomId: envelope.chatRoomId });
      }
      return;
    }
    if (result.value.userId !== userId) {
      this.authenticationFailed(socket, state, "INVALID");
      return;
    }

    if (envelope.event === "message") {
      socket.emit("message", {
        messageId: envelope.data.messageId,
        senderId: envelope.data.senderId,
        content: envelope.data.isDeleted ? null : envelope.data.content,
        isMine: envelope.data.senderId === userId,
        isDeleted: envelope.data.isDeleted,
        readAt: envelope.data.readAt,
        createdAt: envelope.data.createdAt,
      });
      return;
    }
    if (envelope.event === "read") {
      if (envelope.data.readerId === userId) return;
      socket.emit("read", {
        chatRoomId: envelope.chatRoomId,
        lastReadMessageId: envelope.data.lastReadMessageId,
      });
      return;
    }
    socket.emit("message_deleted", {
      chatRoomId: envelope.chatRoomId,
      messageId: envelope.data.messageId,
    });
  }

  private current(socket: Socket, state: ConnectionState, token: string, userId: number): boolean {
    return socket.connected && this.states.get(socket.id) === state
      && state.phase === "authenticated" && state.accessToken === token && state.userId === userId;
  }

  private async revokeRoom(socket: Socket, state: ConnectionState, roomId: number): Promise<void> {
    state.subscriptions.delete(roomId);
    await socket.leave(roomName(roomId));
  }

  private authenticationFailed(socket: Socket, state: ConnectionState, reason: string): void {
    if (state.phase === "closed") return;
    clearTimeout(state.authTimer);
    state.phase = "closed";
    state.accessToken = undefined;
    state.subscriptions.clear();
    socket.emit("auth_error", { reason });
    setImmediate(() => {
      if (socket.connected) socket.disconnect(true);
    });
  }

  private async closeRedis(client: RedisClientType): Promise<void> {
    if (client.isOpen) await client.close();
  }

  private async forceClose(): Promise<void> {
    this.httpServer.closeAllConnections();
    if (this.httpServer.listening) {
      await new Promise<void>((resolve) => this.httpServer.close(() => resolve()));
    }
    for (const client of [this.subscriber, this.healthRedis]) {
      if (client.isOpen) client.destroy();
    }
  }
}

export function createChatServer(config: ChatConfig, logger?: Logger): ChatGateway {
  return logger ? new ChatGateway(config, logger) : new ChatGateway(config);
}
