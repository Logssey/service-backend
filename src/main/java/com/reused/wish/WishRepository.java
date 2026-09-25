package com.reused.wish;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.reused.listing.query.ListingSummaryResponse;
import com.reused.user.dto.response.UserSummaryResponse;

@Repository
class WishRepository {

	private final JdbcTemplate jdbc;

	WishRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	Optional<LockedListing> lockListing(long listingId) {
		return jdbc.query("""
				SELECT l.wish_count, l.status, l.deleted_at,
				       (u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL) AS seller_available
				FROM listings l JOIN users u ON u.user_id = l.seller_id
				WHERE l.listing_id = ? FOR UPDATE OF l
				""", (rs, row) -> new LockedListing(rs.getInt("wish_count"),
				!"HIDDEN".equals(rs.getString("status")) && rs.getTimestamp("deleted_at") == null
				&& rs.getBoolean("seller_available")), listingId)
				.stream().findFirst();
	}

	int add(long userId, long listingId) {
		return jdbc.update("""
				INSERT INTO wishes (user_id, listing_id) VALUES (?, ?)
				ON CONFLICT (user_id, listing_id) DO NOTHING
				""", userId, listingId);
	}

	int remove(long userId, long listingId) {
		return jdbc.update("DELETE FROM wishes WHERE user_id = ? AND listing_id = ?", userId, listingId);
	}

	void updateCount(long listingId, int delta) {
		jdbc.update("UPDATE listings SET wish_count = wish_count + ? WHERE listing_id = ?", delta, listingId);
	}

	List<WishItem> list(long userId, WishCursor cursor, int limit) {
		String sql = """
				SELECT w.wish_id, w.created_at AS wished_at,
				       l.listing_id, l.title, l.price, l.status, l.item_condition,
				       l.wish_count, l.created_at, u.user_id AS seller_id,
				       u.nickname AS seller_nickname, u.profile_image_url AS seller_profile_image_url
				FROM wishes w
				JOIN listings l ON l.listing_id = w.listing_id
				JOIN users u ON u.user_id = l.seller_id
				WHERE w.user_id = ? AND l.deleted_at IS NULL AND l.status <> 'HIDDEN'
				  AND u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL
				  AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = w.user_id AND b.blocked_id = l.seller_id)
				""";
		Object[] args;
		if (cursor == null) {
			args = new Object[] { userId, limit };
		}
		else {
			sql += " AND (w.created_at, w.wish_id) < (?, ?)";
			args = new Object[] { userId, Timestamp.from(cursor.createdAt()), cursor.wishId(), limit };
		}
		sql += " ORDER BY w.created_at DESC, w.wish_id DESC LIMIT ?";
		return jdbc.query(sql, (rs, row) -> new WishItem(rs.getLong("wish_id"),
				rs.getTimestamp("wished_at").toInstant(),
				new ListingSummaryResponse(rs.getLong("listing_id"), rs.getString("title"), rs.getInt("price"),
						rs.getString("status"), rs.getString("item_condition"), null, rs.getInt("wish_count"),
						new UserSummaryResponse(rs.getLong("seller_id"), rs.getString("seller_nickname"),
								rs.getString("seller_profile_image_url")), rs.getTimestamp("created_at").toInstant())), args);
	}

	record LockedListing(int wishCount, boolean visible) {
	}

	record WishItem(long wishId, Instant wishedAt, ListingSummaryResponse listing) {
	}
}
