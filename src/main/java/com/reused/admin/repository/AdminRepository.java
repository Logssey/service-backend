package com.reused.admin.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.reused.admin.dto.AdminListingResponse;
import com.reused.admin.dto.AdminListingSearchRequest;
import com.reused.admin.dto.AdminTradeResponse;
import com.reused.admin.dto.AdminTradeSearchRequest;
import com.reused.trade.query.ListingBriefResponse;
import com.reused.user.dto.response.UserSummaryResponse;

@Repository
public class AdminRepository {

	private static final RowMapper<AdminListingResponse> LISTING_MAPPER = (rs, rowNum) ->
			new AdminListingResponse(
					rs.getLong("listing_id"),
					rs.getString("title"),
					rs.getInt("price"),
					rs.getString("status"),
					new UserSummaryResponse(rs.getLong("seller_id"), rs.getString("seller_nickname"),
							rs.getString("seller_profile_image_url")),
					rs.getLong("report_count"),
					rs.getTimestamp("deleted_at") != null,
					instant(rs, "created_at"));

	private static final RowMapper<AdminTradeResponse> TRADE_MAPPER = (rs, rowNum) ->
			new AdminTradeResponse(
					rs.getLong("trade_id"),
					rs.getString("status"),
					new ListingBriefResponse(rs.getLong("listing_id"), rs.getString("listing_title"),
							rs.getInt("listing_price"), null),
					new UserSummaryResponse(rs.getLong("seller_id"), rs.getString("seller_nickname"),
							rs.getString("seller_profile_image_url")),
					new UserSummaryResponse(rs.getLong("buyer_id"), rs.getString("buyer_nickname"),
							rs.getString("buyer_profile_image_url")),
					instant(rs, "requested_at"),
					nullableInstant(rs, "completed_at"));

	private final NamedParameterJdbcTemplate jdbc;

	public AdminRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<AdminListingResponse> findListings(AdminListingSearchRequest search,
			Instant cursorCreatedAt, Long cursorId, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT l.listing_id, l.title, l.price, l.status, l.deleted_at, l.created_at,
				       seller.user_id AS seller_id, seller.nickname AS seller_nickname,
				       seller.profile_image_url AS seller_profile_image_url,
				       (SELECT count(*) FROM reports report
				        WHERE report.target_type = 'LISTING' AND report.target_id = l.listing_id) AS report_count
				FROM listings l
				JOIN users seller ON seller.user_id = l.seller_id
				WHERE 1 = 1
				""");
		MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
		if (search.status() != null) {
			sql.append(" AND l.status = :status");
			params.addValue("status", search.status());
		}
		if (search.keyword() != null) {
			sql.append(" AND (l.title ILIKE :keyword ESCAPE '!' OR l.description ILIKE :keyword ESCAPE '!')");
			params.addValue("keyword", "%" + escapeLike(search.keyword()) + "%");
		}
		if (search.sellerId() != null) {
			sql.append(" AND l.seller_id = :sellerId");
			params.addValue("sellerId", search.sellerId());
		}
		if (cursorCreatedAt != null) {
			sql.append(" AND (l.created_at < :cursorCreatedAt OR "
					+ "(l.created_at = :cursorCreatedAt AND l.listing_id < :cursorId))");
			params.addValue("cursorCreatedAt", offset(cursorCreatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
					.addValue("cursorId", cursorId);
		}
		sql.append(" ORDER BY l.created_at DESC, l.listing_id DESC LIMIT :limit");
		return jdbc.query(sql.toString(), params, LISTING_MAPPER);
	}

	public List<AdminTradeResponse> findTrades(AdminTradeSearchRequest search,
			Instant cursorRequestedAt, Long cursorId, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT t.trade_id, t.status, t.requested_at, t.completed_at,
				       l.listing_id, l.title AS listing_title, l.price AS listing_price,
				       seller.user_id AS seller_id, seller.nickname AS seller_nickname,
				       seller.profile_image_url AS seller_profile_image_url,
				       buyer.user_id AS buyer_id, buyer.nickname AS buyer_nickname,
				       buyer.profile_image_url AS buyer_profile_image_url
				FROM trades t
				JOIN listings l ON l.listing_id = t.listing_id
				JOIN users seller ON seller.user_id = t.seller_id
				JOIN users buyer ON buyer.user_id = t.buyer_id
				WHERE 1 = 1
				""");
		MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
		if (search.status() != null) {
			sql.append(" AND t.status = :status");
			params.addValue("status", search.status());
		}
		if (search.userId() != null) {
			sql.append(" AND (t.seller_id = :userId OR t.buyer_id = :userId)");
			params.addValue("userId", search.userId());
		}
		if (cursorRequestedAt != null) {
			sql.append(" AND (t.requested_at < :cursorRequestedAt OR "
					+ "(t.requested_at = :cursorRequestedAt AND t.trade_id < :cursorId))");
			params.addValue("cursorRequestedAt", offset(cursorRequestedAt), Types.TIMESTAMP_WITH_TIMEZONE)
					.addValue("cursorId", cursorId);
		}
		sql.append(" ORDER BY t.requested_at DESC, t.trade_id DESC LIMIT :limit");
		return jdbc.query(sql.toString(), params, TRADE_MAPPER);
	}

	public Optional<ListingState> findListingForUpdate(Long listingId) {
		return jdbc.query("""
				SELECT listing_id, status
				FROM listings
				WHERE listing_id = :listingId AND deleted_at IS NULL
				FOR UPDATE
				""", new MapSqlParameterSource("listingId", listingId),
				(rs, rowNum) -> new ListingState(rs.getLong("listing_id"), rs.getString("status")))
				.stream().findFirst();
	}

	public boolean hasActiveTrade(Long listingId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM trades
				    WHERE listing_id = :listingId AND status IN ('REQUESTED', 'ACCEPTED')
				)
				""", new MapSqlParameterSource("listingId", listingId), Boolean.class));
	}

	public void updateListingStatus(Long listingId, String status, Instant changedAt) {
		jdbc.update("""
				UPDATE listings
				SET status = :status, updated_at = :changedAt
				WHERE listing_id = :listingId
				""", new MapSqlParameterSource("listingId", listingId)
				.addValue("status", status)
				.addValue("changedAt", offset(changedAt), Types.TIMESTAMP_WITH_TIMEZONE));
	}

	public void softDeleteListing(Long listingId, Long actorId, Instant changedAt) {
		jdbc.update("""
				UPDATE listings
				SET deleted_at = :changedAt, deleted_by = :actorId, updated_at = :changedAt
				WHERE listing_id = :listingId
				""", new MapSqlParameterSource("listingId", listingId)
				.addValue("actorId", actorId)
				.addValue("changedAt", offset(changedAt), Types.TIMESTAMP_WITH_TIMEZONE));
	}

	public Optional<String> findLatestHiddenPreviousStatus(Long listingId) {
		return jdbc.query("""
				SELECT detail ->> 'beforeStatus' AS before_status
				FROM audit_logs
				WHERE target_type = 'LISTING' AND target_id = :listingId
				  AND action IN ('LISTING_HIDE', 'ADMIN_LISTING_HIDE') AND result = 'SUCCESS'
				  AND detail ->> 'beforeStatus' IN ('ON_SALE', 'RESERVED', 'COMPLETED')
				ORDER BY audit_log_id DESC
				LIMIT 1
				""", new MapSqlParameterSource("listingId", listingId),
				(rs, rowNum) -> rs.getString("before_status")).stream().findFirst();
	}

	/**
	 * HIDDEN is a visibility state stored in the same column as the trade-derived listing state.
	 * A trade may finish while the listing stays hidden, so restoration must use current trade truth
	 * instead of blindly replaying the status captured by the hide audit.
	 */
	public String deriveRestoredStatus(Long listingId, String hiddenFromStatus) {
		return jdbc.queryForObject("""
				SELECT CASE
				         WHEN EXISTS (
				             SELECT 1 FROM trades
				             WHERE listing_id = :listingId AND status = 'COMPLETED'
				         ) THEN 'COMPLETED'
				         WHEN EXISTS (
				             SELECT 1 FROM trades
				             WHERE listing_id = :listingId AND status = 'ACCEPTED'
				         ) THEN 'RESERVED'
				         WHEN :hiddenFromStatus = 'COMPLETED' THEN 'COMPLETED'
				         ELSE 'ON_SALE'
				       END
				""", new MapSqlParameterSource("listingId", listingId)
				.addValue("hiddenFromStatus", hiddenFromStatus), String.class);
	}

	private static String escapeLike(String value) {
		return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	private static OffsetDateTime offset(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getTimestamp(column).toInstant();
	}

	private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
		var value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public record ListingState(Long listingId, String status) {
	}
}
