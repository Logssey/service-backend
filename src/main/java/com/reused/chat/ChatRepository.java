package com.reused.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.reused.trade.query.ListingBriefResponse;
import com.reused.user.dto.response.UserSummaryResponse;

@Repository
class ChatRepository {
	private final JdbcTemplate jdbc;
	ChatRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	Optional<Listing> listing(long id, boolean lock) {
		return jdbc.query("""
				SELECT l.seller_id, l.deleted_at, l.status, u.status AS seller_status, u.withdrawn_at
				FROM listings l JOIN users u ON u.user_id = l.seller_id
				WHERE l.listing_id = ?
				""" + (lock ? " FOR UPDATE OF l" : ""), (rs, n) -> new Listing(rs.getLong("seller_id"),
				!"HIDDEN".equals(rs.getString("status")) && rs.getTimestamp("deleted_at") == null
				&& !"WITHDRAWN".equals(rs.getString("seller_status")) && rs.getTimestamp("withdrawn_at") == null), id)
				.stream().findFirst();
	}

	ChatResponses.RoomCreated create(long listing, long seller, long buyer) {
		List<Long> inserted = jdbc.query("""
				INSERT INTO chat_rooms (listing_id, seller_id, buyer_id, trade_id)
				VALUES (?, ?, ?, (SELECT trade_id FROM trades WHERE listing_id = ? AND buyer_id = ?
				ORDER BY trade_id DESC LIMIT 1))
				ON CONFLICT (listing_id, buyer_id) DO NOTHING RETURNING chat_room_id
				""", (rs, n) -> rs.getLong(1), listing, seller, buyer, listing, buyer);
		if (!inserted.isEmpty()) return new ChatResponses.RoomCreated(inserted.getFirst(), true);
		return new ChatResponses.RoomCreated(jdbc.queryForObject(
				"SELECT chat_room_id FROM chat_rooms WHERE listing_id = ? AND buyer_id = ?", Long.class, listing, buyer), false);
	}

	Optional<Room> room(long id, boolean lock) {
		return jdbc.query("SELECT * FROM chat_rooms WHERE chat_room_id = ?" + (lock ? " FOR UPDATE" : ""),
				(rs, n) -> new Room(rs.getLong("chat_room_id"), rs.getLong("seller_id"), rs.getLong("buyer_id")), id)
				.stream().findFirst();
	}

	boolean withdrawn(long id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM users WHERE user_id = ? AND (status = 'WITHDRAWN' OR withdrawn_at IS NOT NULL))
				""", Boolean.class, id));
	}

	List<RoomRow> rooms(long user, ChatRoomCursor cursor, int limit) {
		String sql = """
				SELECT cr.*, COALESCE(cr.last_message_at, cr.created_at) AS activity_at,
				       l.title, l.price, u.user_id AS counterparty_id, u.nickname, u.profile_image_url,
				       (SELECT CASE WHEN m.deleted_at IS NULL THEN m.content ELSE NULL END FROM messages m
				        WHERE m.chat_room_id = cr.chat_room_id ORDER BY m.message_id DESC LIMIT 1) AS last_message,
				       (SELECT count(*) FROM messages m WHERE m.chat_room_id = cr.chat_room_id
				        AND m.sender_id <> ? AND m.read_at IS NULL AND m.deleted_at IS NULL) AS unread_count
				FROM chat_rooms cr JOIN listings l ON l.listing_id = cr.listing_id
				JOIN users u ON u.user_id = CASE WHEN cr.seller_id = ? THEN cr.buyer_id ELSE cr.seller_id END
				WHERE (cr.seller_id = ? OR cr.buyer_id = ?)
				AND NOT EXISTS (SELECT 1 FROM blocks b WHERE
				 (b.blocker_id = cr.seller_id AND b.blocked_id = cr.buyer_id)
				 OR (b.blocker_id = cr.buyer_id AND b.blocked_id = cr.seller_id))
				""";
		Object[] args;
		if (cursor == null) args = new Object[] {user, user, user, user, limit};
		else {
			sql += " AND (COALESCE(cr.last_message_at, cr.created_at), cr.chat_room_id) < (?, ?)";
			args = new Object[] {user, user, user, user, Timestamp.from(cursor.activityAt()), cursor.roomId(), limit};
		}
		sql += " ORDER BY COALESCE(cr.last_message_at, cr.created_at) DESC, cr.chat_room_id DESC LIMIT ?";
		return jdbc.query(sql, (rs, n) -> new RoomRow(new ChatResponses.RoomSummary(rs.getLong("chat_room_id"),
				new ListingBriefResponse(rs.getLong("listing_id"), rs.getString("title"), rs.getInt("price"), null),
				new UserSummaryResponse(rs.getLong("counterparty_id"), rs.getString("nickname"), rs.getString("profile_image_url")),
				rs.getString("last_message"), instant(rs, "last_message_at"), rs.getLong("unread_count"),
				(Long) rs.getObject("trade_id")), instant(rs, "activity_at")), args);
	}

	ChatResponses.Message send(long room, long sender, String content) {
		ChatResponses.Message message = jdbc.queryForObject("""
				INSERT INTO messages (chat_room_id, sender_id, content, created_at) VALUES (?, ?, ?, clock_timestamp()) RETURNING *
				""", (rs, n) -> message(rs, sender), room, sender, content);
		jdbc.update("UPDATE chat_rooms SET last_message_at = GREATEST(last_message_at, ?) WHERE chat_room_id = ?",
				Timestamp.from(message.createdAt()), room);
		return message;
	}

	List<ChatResponses.Message> messages(long room, long viewer, long before, int limit) {
		return jdbc.query("SELECT * FROM messages WHERE chat_room_id = ? AND message_id < ? ORDER BY message_id DESC LIMIT ?",
				(rs, n) -> message(rs, viewer), room, before, limit);
	}

	Optional<ChatResponses.Message> message(long room, long message, long viewer) {
		return jdbc.query("SELECT * FROM messages WHERE chat_room_id = ? AND message_id = ?",
				(rs, n) -> message(rs, viewer), room, message).stream().findFirst();
	}

	int read(long room, long reader, long lastMessage) {
		return jdbc.update("""
				UPDATE messages SET read_at = clock_timestamp()
				WHERE chat_room_id = ? AND sender_id <> ? AND message_id <= ? AND read_at IS NULL
				""", room, reader, lastMessage);
	}

	long unread(long room, long viewer) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM messages WHERE chat_room_id = ? AND sender_id <> ? AND read_at IS NULL AND deleted_at IS NULL
				""", Long.class, room, viewer);
	}

	int delete(long room, long message) {
		return jdbc.update("UPDATE messages SET deleted_at = clock_timestamp() WHERE chat_room_id = ? AND message_id = ? AND deleted_at IS NULL",
				room, message);
	}

	private static ChatResponses.Message message(ResultSet rs, long viewer) throws SQLException {
		boolean deleted = rs.getTimestamp("deleted_at") != null;
		return new ChatResponses.Message(rs.getLong("message_id"), rs.getLong("sender_id"),
				deleted ? null : rs.getString("content"), rs.getLong("sender_id") == viewer, deleted,
				instant(rs, "read_at"), instant(rs, "created_at"));
	}
	private static Instant instant(ResultSet rs, String name) throws SQLException {
		Timestamp value = rs.getTimestamp(name);
		return value == null ? null : value.toInstant();
	}
	record Listing(long sellerId, boolean available) {}
	record Room(long id, long sellerId, long buyerId) {
		boolean participant(long user) { return user == sellerId || user == buyerId; }
		long other(long user) { return user == sellerId ? buyerId : sellerId; }
	}
	record RoomRow(ChatResponses.RoomSummary summary, Instant activityAt) {}
}
