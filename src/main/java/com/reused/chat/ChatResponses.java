package com.reused.chat;

import java.time.Instant;
import com.reused.trade.query.ListingBriefResponse;
import com.reused.user.dto.response.UserSummaryResponse;

public final class ChatResponses {
	private ChatResponses() {}
	public record RoomCreated(Long chatRoomId, boolean created) {}
	public record RoomSummary(Long chatRoomId, ListingBriefResponse listing, UserSummaryResponse counterparty,
			String lastMessage, Instant lastMessageAt, long unreadCount, Long tradeId) {}
	public record Message(Long messageId, Long senderId, String content, boolean isMine,
			boolean isDeleted, Instant readAt, Instant createdAt) {}
	public record Read(Long chatRoomId, long unreadCount) {}
	public record Session(Long userId) {}
	public record Subscription(Long userId, Long chatRoomId) {}
}
