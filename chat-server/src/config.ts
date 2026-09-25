export interface ChatConfig {
  readonly nodeEnv: string;
  readonly host: string;
  readonly port: number;
  readonly apiBaseUrl: URL;
  readonly redisUrl: string;
  readonly allowedOrigins: ReadonlySet<string>;
  readonly requestTimeoutMs: number;
  readonly authTimeoutMs: number;
  readonly maxTokenLength: number;
  readonly maxSubscriptionsPerSocket: number;
  readonly maxClientPayloadBytes: number;
  readonly maxRedisPayloadBytes: number;
  readonly deliveryAuthConcurrency: number;
  readonly deliveryAuthQueueLimit: number;
  readonly shutdownTimeoutMs: number;
}

function integer(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  const raw = env[name];
  if (raw === undefined || raw.trim() === "") return fallback;
  if (!/^\d+$/.test(raw)) throw new Error(`${name} must be an integer`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function apiUrl(raw: string | undefined): URL {
  if (!raw) throw new Error("CHAT_API_BASE_URL is required");
  const value = new URL(raw);
  if (!new Set(["http:", "https:"]).has(value.protocol)) {
    throw new Error("CHAT_API_BASE_URL must use http or https");
  }
  if (value.username || value.password || value.search || value.hash) {
    throw new Error("CHAT_API_BASE_URL must not contain credentials, query, or fragment");
  }
  value.pathname = "/";
  return value;
}

function redisUrl(raw: string | undefined): string {
  if (!raw) throw new Error("CHAT_REDIS_URL is required");
  const value = new URL(raw);
  if (!new Set(["redis:", "rediss:"]).has(value.protocol)) {
    throw new Error("CHAT_REDIS_URL must use redis or rediss");
  }
  return value.toString();
}

function origins(raw: string | undefined, production: boolean): ReadonlySet<string> {
  const values = (raw ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean)
    .map((rawOrigin) => {
      if (rawOrigin === "*") throw new Error("CHAT_ALLOWED_ORIGINS does not allow wildcards");
      const value = new URL(rawOrigin);
      if (!new Set(["http:", "https:"]).has(value.protocol)
          || value.username || value.password || value.search || value.hash
          || (value.pathname !== "/" && value.pathname !== "")) {
        throw new Error("CHAT_ALLOWED_ORIGINS contains an invalid origin");
      }
      return value.origin;
    });
  const unique = new Set(values);
  if (production && unique.size === 0) {
    throw new Error("CHAT_ALLOWED_ORIGINS is required in production");
  }
  return unique;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ChatConfig {
  const nodeEnv = env.NODE_ENV?.trim() || "development";
  const production = nodeEnv === "production";
  return {
    nodeEnv,
    host: env.HOST?.trim() || "0.0.0.0",
    port: integer(env, "PORT", 3001, production ? 1 : 0, 65_535),
    apiBaseUrl: apiUrl(env.CHAT_API_BASE_URL),
    redisUrl: redisUrl(env.CHAT_REDIS_URL),
    allowedOrigins: origins(env.CHAT_ALLOWED_ORIGINS, production),
    requestTimeoutMs: integer(env, "CHAT_REQUEST_TIMEOUT_MS", 2_000, 100, 30_000),
    authTimeoutMs: 5_000,
    maxTokenLength: integer(env, "CHAT_MAX_TOKEN_LENGTH", 8_192, 256, 16_384),
    maxSubscriptionsPerSocket: integer(env, "CHAT_MAX_SUBSCRIPTIONS", 50, 1, 200),
    maxClientPayloadBytes: integer(env, "CHAT_MAX_CLIENT_PAYLOAD_BYTES", 16_384, 1_024, 65_536),
    maxRedisPayloadBytes: integer(env, "CHAT_MAX_REDIS_PAYLOAD_BYTES", 65_536, 1_024, 262_144),
    deliveryAuthConcurrency: integer(env, "CHAT_DELIVERY_AUTH_CONCURRENCY", 50, 1, 500),
    deliveryAuthQueueLimit: integer(env, "CHAT_DELIVERY_AUTH_QUEUE_LIMIT", 2_000, 1, 20_000),
    shutdownTimeoutMs: integer(env, "CHAT_SHUTDOWN_TIMEOUT_MS", 10_000, 1_000, 30_000),
  };
}
