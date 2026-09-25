package com.reused.trade.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.reused.user.dto.response.UserSummaryResponse;

@Repository
class TradeQueryRepository {

	private static final RowMapper<TradeSummaryResponse> SUMMARY_MAPPER = (rs, rowNum) ->
			new TradeSummaryResponse(
					rs.getLong("trade_id"),
					rs.getString("status"),
					new ListingBriefResponse(rs.getLong("listing_id"), rs.getString("listing_title"),
							rs.getInt("listing_price"), null),
					new UserSummaryResponse(rs.getLong("counterparty_id"),
							rs.getString("counterparty_nickname"), rs.getString("counterparty_profile_image_url")),
					rs.getString("my_role"),
					instant(rs, "requested_at"),
					nullableInstant(rs, "completed_at"),
					rs.getBoolean("review_written"));

	private static final RowMapper<TradeDetailBase> DETAIL_MAPPER = (rs, rowNum) ->
			new TradeDetailBase(
					rs.getLong("trade_id"),
					rs.getString("status"),
					new ListingBriefResponse(rs.getLong("listing_id"), rs.getString("listing_title"),
							rs.getInt("listing_price"), null),
					new UserSummaryResponse(rs.getLong("seller_id"), rs.getString("seller_nickname"),
							rs.getString("seller_profile_image_url")),
					new UserSummaryResponse(rs.getLong("buyer_id"), rs.getString("buyer_nickname"),
							rs.getString("buyer_profile_image_url")),
					rs.getString("my_role"),
					nullableLong(rs, "chat_room_id"),
					rs.getBoolean("review_written"));

	private final NamedParameterJdbcTemplate jdbc;

	TradeQueryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<TradeSummaryResponse> findSummaries(Long userId, TradeSearchRequest search,
			@Nullable TradeCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT t.trade_id, t.status, t.requested_at, t.completed_at,
				       l.listing_id, l.title AS listing_title, l.price AS listing_price,
				       counterparty.user_id AS counterparty_id,
				       counterparty.nickname AS counterparty_nickname,
				       counterparty.profile_image_url AS counterparty_profile_image_url,
				       CASE WHEN t.buyer_id = :userId THEN 'BUYER' ELSE 'SELLER' END AS my_role,
				       EXISTS (SELECT 1 FROM reviews r
				               WHERE r.trade_id = t.trade_id AND r.reviewer_id = :userId) AS review_written
				FROM trades t
				JOIN listings l ON l.listing_id = t.listing_id
				JOIN users counterparty ON counterparty.user_id =
				     CASE WHEN t.buyer_id = :userId THEN t.seller_id ELSE t.buyer_id END
				WHERE (t.buyer_id = :userId OR t.seller_id = :userId)
				""");
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
				.addValue("limit", limit);
		if ("buyer".equals(search.role())) {
			sql.append(" AND t.buyer_id = :userId");
		}
		else if ("seller".equals(search.role())) {
			sql.append(" AND t.seller_id = :userId");
		}
		if (search.status() != null) {
			sql.append(" AND t.status = :status");
			params.addValue("status", search.status());
		}
		if (cursor != null) {
			sql.append(" AND (t.requested_at < :cursorRequestedAt OR "
					+ "(t.requested_at = :cursorRequestedAt AND t.trade_id < :cursorTradeId))");
			params.addValue("cursorRequestedAt", OffsetDateTime.ofInstant(cursor.requestedAt(), ZoneOffset.UTC),
					Types.TIMESTAMP_WITH_TIMEZONE);
			params.addValue("cursorTradeId", cursor.tradeId());
		}
		sql.append(" ORDER BY t.requested_at DESC, t.trade_id DESC LIMIT :limit");
		return jdbc.query(sql.toString(), params, SUMMARY_MAPPER);
	}

	Optional<TradeDetailBase> findDetail(Long tradeId, Long userId) {
		String sql = """
				SELECT t.trade_id, t.status,
				       l.listing_id, l.title AS listing_title, l.price AS listing_price,
				       seller.user_id AS seller_id, seller.nickname AS seller_nickname,
				       seller.profile_image_url AS seller_profile_image_url,
				       buyer.user_id AS buyer_id, buyer.nickname AS buyer_nickname,
				       buyer.profile_image_url AS buyer_profile_image_url,
				       CASE WHEN t.buyer_id = :userId THEN 'BUYER' ELSE 'SELLER' END AS my_role,
				       (SELECT min(cr.chat_room_id) FROM chat_rooms cr
				        WHERE cr.trade_id = t.trade_id
				           OR (cr.listing_id = t.listing_id AND cr.buyer_id = t.buyer_id)) AS chat_room_id,
				       EXISTS (SELECT 1 FROM reviews r
				               WHERE r.trade_id = t.trade_id AND r.reviewer_id = :userId) AS review_written
				FROM trades t
				JOIN listings l ON l.listing_id = t.listing_id
				JOIN users seller ON seller.user_id = t.seller_id
				JOIN users buyer ON buyer.user_id = t.buyer_id
				WHERE t.trade_id = :tradeId
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("tradeId", tradeId)
				.addValue("userId", userId);
		return jdbc.query(sql, params, DETAIL_MAPPER).stream().findFirst();
	}

	List<TradeHistoryResponse> findHistories(Long tradeId) {
		return jdbc.query("""
				SELECT after_status, created_at, reason
				FROM trade_status_histories
				WHERE trade_id = :tradeId
				ORDER BY created_at ASC, history_id ASC
				""", new MapSqlParameterSource("tradeId", tradeId),
				(rs, rowNum) -> new TradeHistoryResponse(rs.getString("after_status"),
						instant(rs, "created_at"), rs.getString("reason")));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getTimestamp(column).toInstant();
	}

	private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
		var value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	record TradeDetailBase(Long tradeId, String status, ListingBriefResponse listing,
			UserSummaryResponse seller, UserSummaryResponse buyer, String myRole,
			Long chatRoomId, boolean reviewWritten) {
	}
}
