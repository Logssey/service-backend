import type { ChatConfig } from "./config.js";

export type ApiFailure = "unauthorized" | "forbidden" | "unavailable";
export type ApiResult<T> = { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly failure: ApiFailure };

interface SessionResponse {
  readonly userId: number;
}

interface SubscriptionResponse {
  readonly userId: number;
  readonly chatRoomId: number;
}

const MAX_API_RESPONSE_BYTES = 8_192;

function positiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

async function discard(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    // The response is already unusable; callers only need its status classification.
  }
}

async function limitedJson(response: Response): Promise<unknown> {
  const length = response.headers.get("content-length");
  if (length !== null && Number(length) > MAX_API_RESPONSE_BYTES) {
    await discard(response);
    throw new Error("API response too large");
  }
  if (!response.body) throw new Error("API response body missing");

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let bytes = 0;
  let text = "";
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      bytes += part.value.byteLength;
      if (bytes > MAX_API_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("API response too large");
      }
      text += decoder.decode(part.value, { stream: true });
    }
    text += decoder.decode();
    return JSON.parse(text) as unknown;
  } finally {
    reader.releaseLock();
  }
}

export class BackendClient {
  constructor(private readonly config: Pick<ChatConfig, "apiBaseUrl" | "requestTimeoutMs">) {}

  async session(accessToken: string): Promise<ApiResult<SessionResponse>> {
    const response = await this.get("/api/v1/chat/session", accessToken);
    if (!response.ok) return response;
    const body = response.value;
    if (typeof body !== "object" || body === null) {
      return { ok: false, failure: "unavailable" };
    }
    const userId = (body as Record<string, unknown>).userId;
    if (!positiveId(userId)) return { ok: false, failure: "unavailable" };
    return { ok: true, value: { userId } };
  }

  async subscription(accessToken: string, chatRoomId: number): Promise<ApiResult<SubscriptionResponse>> {
    const response = await this.get(`/api/v1/chat-rooms/${chatRoomId}/subscription`, accessToken);
    if (!response.ok) return response;
    const body = response.value;
    if (typeof body !== "object" || body === null) {
      return { ok: false, failure: "unavailable" };
    }
    const record = body as Record<string, unknown>;
    if (!positiveId(record.userId) || !positiveId(record.chatRoomId)
        || record.chatRoomId !== chatRoomId) {
      return { ok: false, failure: "unavailable" };
    }
    return { ok: true, value: { userId: record.userId, chatRoomId: record.chatRoomId } };
  }

  private async get(path: string, accessToken: string): Promise<ApiResult<unknown>> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.requestTimeoutMs);
    timer.unref();
    try {
      const response = await fetch(new URL(path, this.config.apiBaseUrl), {
        method: "GET",
        headers: {
          accept: "application/json",
          authorization: `Bearer ${accessToken}`,
        },
        redirect: "error",
        signal: controller.signal,
      });
      if (response.status === 401) {
        await discard(response);
        return { ok: false, failure: "unauthorized" };
      }
      if (response.status === 403 || response.status === 404) {
        await discard(response);
        return { ok: false, failure: "forbidden" };
      }
      if (response.status !== 200) {
        await discard(response);
        return { ok: false, failure: "unavailable" };
      }
      try {
        return { ok: true, value: await limitedJson(response) };
      } catch {
        return { ok: false, failure: "unavailable" };
      }
    } catch {
      return { ok: false, failure: "unavailable" };
    } finally {
      clearTimeout(timer);
    }
  }
}
