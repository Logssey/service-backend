export const CHAT_CHANNEL_PATTERN = "reused:chat:room:*";
const CHAT_CHANNEL = /^reused:chat:room:([1-9]\d*)$/;

export type ChatEvent = "message" | "read" | "message_deleted";

interface MessageData {
  readonly messageId: number;
  readonly senderId: number;
  readonly content: string | null;
  readonly isDeleted: boolean;
  readonly readAt: string | null;
  readonly createdAt: string;
}

interface ReadData {
  readonly chatRoomId: number;
  readonly lastReadMessageId: number;
  readonly readerId: number;
}

interface DeletedData {
  readonly chatRoomId: number;
  readonly messageId: number;
}

export type ValidEnvelope =
  | { readonly event: "message"; readonly chatRoomId: number; readonly data: MessageData }
  | { readonly event: "read"; readonly chatRoomId: number; readonly data: ReadData }
  | { readonly event: "message_deleted"; readonly chatRoomId: number; readonly data: DeletedData };

export function positiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

export function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function timestamp(value: unknown, nullable: boolean): value is string | null {
  if (nullable && value === null) return true;
  return typeof value === "string" && value.length > 0 && value.length <= 64
    && Number.isFinite(Date.parse(value));
}

function parseRoom(channel: string): number | null {
  const match = CHAT_CHANNEL.exec(channel);
  if (!match) return null;
  const value = Number(match[1]);
  return positiveId(value) ? value : null;
}

export function parseEnvelope(channel: string, raw: string, maximumBytes: number): ValidEnvelope | null {
  if (channel.length > 128 || Buffer.byteLength(raw, "utf8") > maximumBytes) return null;
  const channelRoomId = parseRoom(channel);
  if (channelRoomId === null) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
  if (!record(parsed) || !positiveId(parsed.chatRoomId)
      || parsed.chatRoomId !== channelRoomId || !record(parsed.data)) {
    return null;
  }

  const data = parsed.data;
  if (parsed.event === "message") {
    if (!positiveId(data.messageId) || !positiveId(data.senderId)
        || !(data.content === null || (typeof data.content === "string" && data.content.length <= 1_000))
        || typeof data.isDeleted !== "boolean"
        || !timestamp(data.readAt, true) || typeof data.createdAt !== "string"
        || !timestamp(data.createdAt, false)) {
      return null;
    }
    return {
      event: "message",
      chatRoomId: channelRoomId,
      data: {
        messageId: data.messageId,
        senderId: data.senderId,
        content: data.content,
        isDeleted: data.isDeleted,
        readAt: data.readAt,
        createdAt: data.createdAt,
      },
    };
  }
  if (parsed.event === "read") {
    if (data.chatRoomId !== channelRoomId || !positiveId(data.lastReadMessageId)
        || !positiveId(data.readerId)) return null;
    return {
      event: "read",
      chatRoomId: channelRoomId,
      data: {
        chatRoomId: channelRoomId,
        lastReadMessageId: data.lastReadMessageId,
        readerId: data.readerId,
      },
    };
  }
  if (parsed.event === "message_deleted") {
    if (data.chatRoomId !== channelRoomId || !positiveId(data.messageId)) return null;
    return {
      event: "message_deleted",
      chatRoomId: channelRoomId,
      data: { chatRoomId: channelRoomId, messageId: data.messageId },
    };
  }
  return null;
}
