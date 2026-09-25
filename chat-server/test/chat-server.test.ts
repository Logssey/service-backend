import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createServer, type Server as HttpServer } from "node:http";
import { createConnection } from "node:net";
import { after, afterEach, before, beforeEach, test } from "node:test";

import { createClient, type RedisClientType } from "redis";
import { io as connectSocket, type Socket } from "socket.io-client";

import { loadConfig, type ChatConfig } from "../src/config.js";
import { createChatServer, type ChatGateway } from "../src/server.js";

const ALLOWED_ORIGIN = "http://chat.test";
const silentLogger = { info(): void {}, warn(): void {}, error(): void {} };

interface TokenState {
  readonly userId: number;
  readonly rooms: Set<number>;
}

class MockBackend {
  private readonly server: HttpServer;
  private readonly tokens = new Map<string, TokenState>();
  readonly authorizationHeaders: string[] = [];
  subscriptionCalls = 0;
  subscriptionDelayMs = 0;
  url = "";

  constructor() {
    this.server = createServer((request, response) => {
      const authorization = request.headers.authorization;
      if (typeof authorization === "string") this.authorizationHeaders.push(authorization);
      const token = typeof authorization === "string" && authorization.startsWith("Bearer ")
        ? authorization.slice("Bearer ".length) : "";
      const state = this.tokens.get(token);
      const pathname = new URL(request.url ?? "/", "http://backend.test").pathname;

      if (request.method === "GET" && pathname === "/api/v1/chat/session") {
        if (!state) return this.json(response, 401, { code: "UNAUTHENTICATED" });
        return this.json(response, 200, { userId: state.userId });
      }
      const subscription = /^\/api\/v1\/chat-rooms\/([1-9]\d*)\/subscription$/.exec(pathname);
      if (request.method === "GET" && subscription) {
        this.subscriptionCalls += 1;
        const roomId = Number(subscription[1]);
        const answer = (): void => {
          if (!state) {
            this.json(response, 401, { code: "UNAUTHENTICATED" });
          } else if (!state.rooms.has(roomId)) {
            this.json(response, 403, { code: "FORBIDDEN" });
          } else {
            this.json(response, 200, { userId: state.userId, chatRoomId: roomId });
          }
        };
        if (this.subscriptionDelayMs > 0) setTimeout(answer, this.subscriptionDelayMs);
        else answer();
        return;
      }
      return this.json(response, 404, { code: "NOT_FOUND" });
    });
  }

  async start(): Promise<void> {
    await new Promise<void>((resolve) => this.server.listen(0, "127.0.0.1", resolve));
    const address = this.server.address();
    if (!address || typeof address === "string") throw new Error("mock backend did not bind");
    this.url = `http://127.0.0.1:${address.port}`;
  }

  async close(): Promise<void> {
    this.server.closeAllConnections();
    if (this.server.listening) {
      await new Promise<void>((resolve) => this.server.close(() => resolve()));
    }
  }

  reset(): void {
    this.tokens.clear();
    this.authorizationHeaders.length = 0;
    this.subscriptionCalls = 0;
    this.subscriptionDelayMs = 0;
  }

  allow(token: string, userId: number, ...rooms: number[]): void {
    this.tokens.set(token, { userId, rooms: new Set(rooms) });
  }

  revokeToken(token: string): void {
    this.tokens.delete(token);
  }

  revokeRoom(token: string, roomId: number): void {
    this.tokens.get(token)?.rooms.delete(roomId);
  }

  private json(response: import("node:http").ServerResponse, status: number, body: unknown): void {
    response.writeHead(status, { "content-type": "application/json" });
    response.end(JSON.stringify(body));
  }
}

interface RedisContainer {
  readonly id: string;
  readonly url: string;
}

function docker(args: string[]): string {
  const result = spawnSync("docker", args, { encoding: "utf8", timeout: 120_000 });
  if (result.status !== 0) {
    throw new Error(`docker command failed (${result.status ?? "no status"}): ${result.stderr.trim()}`);
  }
  return result.stdout.trim();
}

async function startRedis(): Promise<RedisContainer> {
  const name = `reused-chat-test-${process.pid}-${Date.now()}`;
  const id = docker([
    "run", "--detach", "--rm", "--name", name,
    "--publish", "127.0.0.1::6379",
    "redis:7.4-alpine", "redis-server", "--save", "", "--appendonly", "no",
  ]);
  try {
    let ready = false;
    for (let attempt = 0; attempt < 50; attempt += 1) {
      const ping = spawnSync("docker", ["exec", id, "redis-cli", "ping"], {
        encoding: "utf8",
        timeout: 5_000,
      });
      if (ping.status === 0 && ping.stdout.trim() === "PONG") {
        ready = true;
        break;
      }
      await delay(100);
    }
    if (!ready) throw new Error("Redis test container did not become ready");
    const mapping = docker(["port", id, "6379/tcp"]).split(/\r?\n/, 1)[0] ?? "";
    const port = /:(\d+)$/.exec(mapping)?.[1];
    if (!port) throw new Error("Redis test container port was not published");
    return { id, url: `redis://127.0.0.1:${port}` };
  } catch (error) {
    spawnSync("docker", ["rm", "--force", id], { encoding: "utf8" });
    throw error;
  }
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function rawHttp(port: number, request: string): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    const socket = createConnection({ host: "127.0.0.1", port });
    let response = "";
    const timeout = setTimeout(() => {
      socket.destroy();
      reject(new Error("raw HTTP request timed out"));
    }, 2_000);
    socket.setEncoding("utf8");
    socket.on("connect", () => socket.end(request));
    socket.on("data", (chunk: string) => { response += chunk; });
    socket.on("end", () => {
      clearTimeout(timeout);
      resolve(response);
    });
    socket.on("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });
  });
}

function event<T>(socket: Socket, name: string, timeout = 2_000): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off(name, handler);
      reject(new Error(`Timed out waiting for ${name}`));
    }, timeout);
    const handler = (value: T): void => {
      clearTimeout(timer);
      resolve(value);
    };
    socket.once(name, handler);
  });
}

async function noEvent(socket: Socket, name: string, duration = 180): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const handler = (): void => {
      clearTimeout(timer);
      reject(new Error(`Unexpected ${name} event`));
    };
    const timer = setTimeout(() => {
      socket.off(name, handler);
      resolve();
    }, duration);
    socket.once(name, handler);
  });
}

let redisContainer: RedisContainer;
let publisher: RedisClientType;
let backend: MockBackend;
let gateway: ChatGateway;
let serverUrl: string;
const clients: Socket[] = [];

before(async () => {
  redisContainer = await startRedis();
  backend = new MockBackend();
  await backend.start();
  const config: ChatConfig = {
    nodeEnv: "test",
    host: "127.0.0.1",
    port: 0,
    apiBaseUrl: new URL(backend.url),
    redisUrl: redisContainer.url,
    allowedOrigins: new Set([ALLOWED_ORIGIN]),
    requestTimeoutMs: 500,
    authTimeoutMs: 150,
    maxTokenLength: 512,
    maxSubscriptionsPerSocket: 3,
    maxClientPayloadBytes: 2_048,
    maxRedisPayloadBytes: 4_096,
    deliveryAuthConcurrency: 4,
    deliveryAuthQueueLimit: 50,
    shutdownTimeoutMs: 2_000,
  };
  gateway = createChatServer(config, silentLogger);
  serverUrl = (await gateway.start(0)).url;
  publisher = createClient({ url: redisContainer.url });
  publisher.on("error", () => {});
  await publisher.connect();
});

beforeEach(() => backend.reset());

afterEach(() => {
  for (const client of clients.splice(0)) client.disconnect();
});

after(async () => {
  for (const client of clients.splice(0)) client.disconnect();
  if (publisher?.isOpen) await publisher.close();
  if (gateway) await gateway.close();
  if (backend) await backend.close();
  if (redisContainer?.id) {
    spawnSync("docker", ["rm", "--force", redisContainer.id], { encoding: "utf8", timeout: 30_000 });
  }
});

function client(options: Parameters<typeof connectSocket>[1] = {}): Socket {
  const socket = connectSocket(serverUrl, {
    path: "/socket.io",
    transports: ["websocket"],
    reconnection: false,
    forceNew: true,
    extraHeaders: { Origin: ALLOWED_ORIGIN },
    ...options,
  });
  clients.push(socket);
  return socket;
}

async function connected(options: Parameters<typeof connectSocket>[1] = {}): Promise<Socket> {
  const socket = client(options);
  await event(socket, "connect");
  return socket;
}

async function authenticate(socket: Socket, token: string): Promise<void> {
  const authenticated = event<void>(socket, "authenticated");
  socket.emit("authenticate", { token });
  await authenticated;
}

async function subscribe(socket: Socket, roomId: number): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("subscribe acknowledgement timed out")), 2_000);
    socket.emit("subscribe", { chatRoomId: roomId }, (result: Record<string, unknown>) => {
      clearTimeout(timer);
      resolve(result);
    });
  });
}

async function publish(roomId: number, body: unknown): Promise<void> {
  await publisher.publish(`reused:chat:room:${roomId}`, JSON.stringify(body));
}

function messageEnvelope(roomId: number, messageId: number, senderId = 1): unknown {
  return {
    event: "message",
    chatRoomId: roomId,
    data: {
      messageId,
      senderId,
      content: "hello",
      isMine: "must-not-be-trusted",
      isDeleted: false,
      readAt: null,
      createdAt: "2026-09-25T12:00:00Z",
      internalSecret: "must-not-be-forwarded",
    },
  };
}

test("production config requires an explicit CORS allowlist and test mode accepts port zero", () => {
  assert.throws(() => loadConfig({
    NODE_ENV: "production",
    CHAT_API_BASE_URL: "http://backend:8080",
    CHAT_REDIS_URL: "redis://redis:6379",
  }), /CHAT_ALLOWED_ORIGINS/);
  const config = loadConfig({
    NODE_ENV: "test",
    CHAT_API_BASE_URL: "http://backend:8080",
    CHAT_REDIS_URL: "redis://redis:6379",
    CHAT_ALLOWED_ORIGINS: ALLOWED_ORIGIN,
    PORT: "0",
  });
  assert.equal(config.port, 0);
  assert.deepEqual([...config.allowedOrigins], [ALLOWED_ORIGIN]);
});

test("a malformed HTTP request target returns 400 without crashing the process", async () => {
  const response = await rawHttp(gateway.address.port,
    "GET //[ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
  assert.match(response, /^HTTP\/1\.1 400 /);
  const health = await fetch(`${serverUrl}/healthz`);
  assert.equal(health.status, 200);
});

test("credentials are accepted only through authenticate and missing or invalid auth disconnects", async () => {
  const queryClient = client({ query: { accessToken: "query-secret" } });
  const queryError = await event<Error>(queryClient, "connect_error");
  assert.ok(queryError instanceof Error);
  assert.equal(queryClient.connected, false);
  assert.equal(backend.authorizationHeaders.length, 0);

  const handshakeClient = client({ auth: { accessToken: "handshake-secret" } });
  const handshakeError = await event<Error>(handshakeClient, "connect_error");
  assert.match(handshakeError.message, /authenticate event/);
  assert.equal(backend.authorizationHeaders.length, 0);

  const timedOut = await connected();
  const timeoutError = await event<{ reason: string }>(timedOut, "auth_error");
  assert.equal(timeoutError.reason, "TIMEOUT");
  await event(timedOut, "disconnect");

  const invalid = await connected();
  const invalidError = event<{ reason: string }>(invalid, "auth_error");
  const invalidDisconnect = event(invalid, "disconnect");
  invalid.emit("authenticate", { accessToken: "invalid-token" });
  assert.equal((await invalidError).reason, "INVALID");
  await invalidDisconnect;

  backend.allow("valid-token", 1, 12);
  const valid = await connected();
  await authenticate(valid, "valid-token");
  assert.equal(valid.connected, true);

  backend.allow("alias-token", 2, 12);
  const alias = await connected();
  const aliasAuthenticated = event(alias, "authenticated");
  alias.emit("authenticate", { accessToken: "alias-token" });
  await aliasAuthenticated;

  const ambiguous = await connected();
  const ambiguousError = event<{ reason: string }>(ambiguous, "auth_error");
  ambiguous.emit("authenticate", { token: "valid-token", accessToken: "alias-token" });
  assert.equal((await ambiguousError).reason, "INVALID");
  assert.deepEqual(backend.authorizationHeaders,
    ["Bearer invalid-token", "Bearer valid-token", "Bearer alias-token"]);
});

test("authorized room delivery uses Redis and sanitizes recipient-specific events", async () => {
  backend.allow("user-one", 1, 12);
  backend.allow("user-two", 2, 12);
  const first = await connected();
  const second = await connected();
  await Promise.all([authenticate(first, "user-one"), authenticate(second, "user-two")]);
  assert.deepEqual(await subscribe(first, 12), { ok: true, chatRoomId: 12 });
  assert.deepEqual(await subscribe(second, 12), { ok: true, chatRoomId: 12 });

  const firstMessage = event<Record<string, unknown>>(first, "message");
  const secondMessage = event<Record<string, unknown>>(second, "message");
  await publish(12, messageEnvelope(12, 101));
  assert.deepEqual(await firstMessage, {
    messageId: 101,
    senderId: 1,
    content: "hello",
    isMine: true,
    isDeleted: false,
    readAt: null,
    createdAt: "2026-09-25T12:00:00Z",
  });
  assert.deepEqual(await secondMessage, {
    messageId: 101,
    senderId: 1,
    content: "hello",
    isMine: false,
    isDeleted: false,
    readAt: null,
    createdAt: "2026-09-25T12:00:00Z",
  });

  const readForSecond = event<Record<string, unknown>>(second, "read");
  const noReadEcho = noEvent(first, "read");
  await publish(12, {
    event: "read",
    chatRoomId: 12,
    data: { chatRoomId: 12, lastReadMessageId: 101, readerId: 1 },
  });
  assert.deepEqual(await readForSecond, { chatRoomId: 12, lastReadMessageId: 101 });
  await noReadEcho;

  const deletion = event<Record<string, unknown>>(first, "message_deleted");
  await publish(12, {
    event: "message_deleted",
    chatRoomId: 12,
    data: { chatRoomId: 12, messageId: 101, extra: "not forwarded" },
  });
  assert.deepEqual(await deletion, { chatRoomId: 12, messageId: 101 });

  second.emit("unsubscribe", { chatRoomId: 12 });
  await delay(30);
  const remainingMessage = event(first, "message");
  const unsubscribedGetsNothing = noEvent(second, "message");
  await publish(12, messageEnvelope(12, 102));
  await remainingMessage;
  await unsubscribedGetsNothing;
  assert.ok(backend.subscriptionCalls >= 7, "each delivery must reauthorize each recipient");
});

test("mismatched or oversized Redis envelopes never reach a subscribed client", async () => {
  backend.allow("safe-user", 1, 12);
  const socket = await connected();
  await authenticate(socket, "safe-user");
  await subscribe(socket, 12);

  const mismatch = noEvent(socket, "message");
  await publish(12, messageEnvelope(13, 201));
  await mismatch;

  const oversized = noEvent(socket, "message");
  await publish(12, {
    event: "message",
    chatRoomId: 12,
    data: {
      messageId: 202,
      senderId: 1,
      content: "x".repeat(5_000),
      isDeleted: false,
      readAt: null,
      createdAt: "2026-09-25T12:00:00Z",
    },
  });
  await oversized;
});

test("delivery reauthorization fails closed for revoked rooms and expired tokens", async () => {
  backend.allow("revoked-room", 1, 12);
  backend.allow("expired-token", 2, 12);
  const revoked = await connected();
  const expired = await connected();
  await Promise.all([authenticate(revoked, "revoked-room"), authenticate(expired, "expired-token")]);
  await Promise.all([subscribe(revoked, 12), subscribe(expired, 12)]);

  backend.revokeRoom("revoked-room", 12);
  backend.revokeToken("expired-token");
  const forbidden = event<Record<string, unknown>>(revoked, "forbidden");
  const revokedNoContent = noEvent(revoked, "message");
  const authError = event<{ reason: string }>(expired, "auth_error");
  const expiredNoContent = noEvent(expired, "message");
  const disconnected = event(expired, "disconnect");
  await publish(12, messageEnvelope(12, 301));
  assert.deepEqual(await forbidden, { chatRoomId: 12 });
  assert.equal((await authError).reason, "INVALID");
  await disconnected;
  await Promise.all([revokedNoContent, expiredNoContent]);

  assert.equal(revoked.connected, true);
  const stillLeft = noEvent(revoked, "message");
  await publish(12, messageEnvelope(12, 302));
  await stillLeft;
});

test("subscription count is bounded and health reports Redis readiness", async () => {
  backend.allow("bounded-user", 1, 1, 2, 3, 4);
  const socket = await connected();
  await authenticate(socket, "bounded-user");
  for (let roomId = 10_000; roomId < 15_000; roomId += 1) {
    socket.emit("unsubscribe", { chatRoomId: roomId });
  }
  await delay(20);
  for (const roomId of [1, 2, 3]) {
    assert.deepEqual(await subscribe(socket, roomId), { ok: true, chatRoomId: roomId });
  }
  const forbidden = event<Record<string, unknown>>(socket, "forbidden");
  assert.deepEqual(await subscribe(socket, 4), { ok: false, chatRoomId: 4, code: "FORBIDDEN" });
  assert.deepEqual(await forbidden, { chatRoomId: 4 });

  const health = await fetch(`${serverUrl}/healthz`);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { status: "ok" });
});

test("duplicate and random subscription floods keep backend checks bounded", async () => {
  backend.allow("flood-user", 1, 12);
  backend.subscriptionDelayMs = 100;
  const socket = await connected();
  await authenticate(socket, "flood-user");

  const duplicateResults = await Promise.all(
    Array.from({ length: 50 }, () => subscribe(socket, 12)),
  );
  assert.equal(backend.subscriptionCalls, 1);
  assert.equal(duplicateResults.filter((result) => result.ok === true).length, 1);
  assert.equal(duplicateResults.filter((result) => result.code === "PENDING").length, 49);

  socket.emit("unsubscribe", { chatRoomId: 12 });
  await delay(20);
  backend.reset();
  backend.allow("flood-user", 1);
  backend.subscriptionDelayMs = 100;
  const randomResults = await Promise.all(
    Array.from({ length: 100 }, (_, index) => subscribe(socket, 20_000 + index)),
  );
  assert.equal(backend.subscriptionCalls, 3);
  assert.equal(randomResults.filter((result) => result.code === "FORBIDDEN").length, 100);

  backend.allow("flood-user", 1, 21);
  assert.deepEqual(await subscribe(socket, 21), { ok: true, chatRoomId: 21 });
});
