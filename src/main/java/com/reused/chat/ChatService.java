package com.reused.chat;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.reused.block.BlockService;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.paging.IdPage;
import com.reused.common.security.ActorGuard;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.MarketLocks;
import com.reused.image.service.ListingImageService;
import com.reused.listing.query.CursorPageResponse;
import com.reused.notification.service.NotificationService;
import com.reused.trade.query.ListingBriefResponse;

@Service
@Transactional
public class ChatService {
	private final ChatRepository repository;
	private final ActorGuard actors;
	private final BlockService blocks;
	private final ListingImageService images;
	private final NotificationService notifications;
	private final ChatEventPublisher events;
	private final MarketLocks locks;
	public ChatService(ChatRepository repository, ActorGuard actors, BlockService blocks,
			ListingImageService images, NotificationService notifications, ChatEventPublisher events, MarketLocks locks) {
		this.repository = repository; this.actors = actors; this.blocks = blocks;
		this.images = images; this.notifications = notifications; this.events = events; this.locks = locks;
	}

	public ChatResponses.RoomCreated create(AuthPrincipal principal, Long listingId) {
		long buyer = principalId(principal);
		id(listingId);
		var preview = repository.listing(listingId, false).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		locks.users(buyer, preview.sellerId());
		actors.user(principal, true);
		locks.blockPair(buyer, preview.sellerId());
		var listing = repository.listing(listingId, true).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!listing.available()) throw new BusinessException(ErrorCode.NOT_FOUND);
		if (buyer == listing.sellerId()) throw new BusinessException(ErrorCode.INVALID_INPUT);
		unblocked(buyer, listing.sellerId());
		return repository.create(listingId, listing.sellerId(), buyer);
	}

	@Transactional(readOnly = true)
	public CursorPageResponse<ChatResponses.RoomSummary> rooms(AuthPrincipal principal, String encoded, Integer requestedSize) {
		long user = actors.user(principal, false);
		int size = IdPage.size(requestedSize);
		ChatRoomCursor cursor = encoded == null ? null : ChatRoomCursor.decode(encoded, user);
		List<ChatRepository.RoomRow> rows = repository.rooms(user, cursor, size + 1);
		boolean hasNext = rows.size() > size;
		var page = rows.subList(0, Math.min(size, rows.size()));
		Map<Long, String> thumbnails = images.thumbnailsForListings(page.stream().map(r -> r.summary().listing().listingId()).toList());
		List<ChatResponses.RoomSummary> items = page.stream().map(r -> {
			var s = r.summary(); var l = s.listing();
			return new ChatResponses.RoomSummary(s.chatRoomId(), new ListingBriefResponse(l.listingId(), l.title(),
					l.price(), thumbnails.get(l.listingId())), s.counterparty(), s.lastMessage(), s.lastMessageAt(),
					s.unreadCount(), s.tradeId());
		}).toList();
		String next = hasNext ? new ChatRoomCursor(page.getLast().activityAt(), page.getLast().summary().chatRoomId()).encode(user) : null;
		return new CursorPageResponse<>(items, next, hasNext);
	}

	public ChatResponses.Message send(AuthPrincipal principal, Long roomId, String content) {
		long sender = principalId(principal);
		if (content == null || content.strip().isEmpty() || content.length() > 1000)
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		var preview = participant(roomId, sender, false);
		locks.users(preview.sellerId(), preview.buyerId());
		actors.user(principal, true);
		locks.blockPair(preview.sellerId(), preview.buyerId());
		var room = participant(roomId, sender, true);
		unblocked(room.sellerId(), room.buyerId());
		if (repository.withdrawn(room.other(sender))) throw new BusinessException(ErrorCode.CONFLICT, "탈퇴한 회원에게 메시지를 보낼 수 없습니다.");
		var message = repository.send(roomId, sender, content.strip());
		notifications.createFor(room.other(sender), "CHAT_RECEIVED", "새 메시지", "새 채팅 메시지가 도착했습니다.", "CHAT_ROOM", roomId);
		events.afterCommit("message", roomId, message);
		return message;
	}

	@Transactional(readOnly = true)
	public CursorPageResponse<ChatResponses.Message> messages(AuthPrincipal principal, Long roomId, String cursor, Integer requestedSize) {
		long user = actors.user(principal, false);
		participant(roomId, user, false);
		int size = IdPage.size(requestedSize);
		String scope = "messages:" + roomId + ":" + user;
		long before = IdPage.before(cursor, scope);
		return IdPage.of(repository.messages(roomId, user, before, size + 1), size, scope, ChatResponses.Message::messageId);
	}

	public ChatResponses.Read read(AuthPrincipal principal, Long roomId, Long lastReadMessageId) {
		long user = actors.user(principal, false);
		id(lastReadMessageId);
		participant(roomId, user, true);
		repository.message(roomId, lastReadMessageId, user).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (repository.read(roomId, user, lastReadMessageId) > 0)
			events.afterCommit("read", roomId, Map.of("chatRoomId", roomId, "lastReadMessageId", lastReadMessageId, "readerId", user));
		return new ChatResponses.Read(roomId, repository.unread(roomId, user));
	}

	public void delete(AuthPrincipal principal, Long roomId, Long messageId) {
		long user = actors.user(principal, false);
		id(messageId);
		participant(roomId, user, true);
		var message = repository.message(roomId, messageId, user).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (message.senderId() != user) throw new BusinessException(ErrorCode.FORBIDDEN);
		if (repository.delete(roomId, messageId) > 0)
			events.afterCommit("message_deleted", roomId, Map.of("chatRoomId", roomId, "messageId", messageId));
	}

	@Transactional(readOnly = true)
	public ChatResponses.Session session(AuthPrincipal principal) { return new ChatResponses.Session(actors.user(principal, false)); }

	@Transactional(readOnly = true)
	public ChatResponses.Subscription subscription(AuthPrincipal principal, Long roomId) {
		long user = actors.user(principal, false);
		var room = participant(roomId, user, false);
		unblocked(room.sellerId(), room.buyerId());
		return new ChatResponses.Subscription(user, roomId);
	}

	private ChatRepository.Room participant(Long roomId, long user, boolean lock) {
		id(roomId);
		var room = repository.room(roomId, lock).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!room.participant(user)) throw new BusinessException(ErrorCode.FORBIDDEN);
		return room;
	}
	private void unblocked(long first, long second) {
		if (blocks.eitherDirection(first, second)) throw new BusinessException(ErrorCode.FORBIDDEN);
	}
	private static void id(Long id) { if (id == null || id <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT); }
	private static long principalId(AuthPrincipal principal) {
		if (principal == null || principal.userId() == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		return principal.userId();
	}
}
