package com.reused.listing.query;

import java.math.BigDecimal;
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

import com.reused.category.dto.response.CategoryResponse;
import com.reused.user.dto.response.UserSummaryResponse;

/** SQL projections keep public feed queries independent of listing JPA mutations. */
@Repository
class ListingQueryRepository {

	private static final RowMapper<ListingSummaryResponse> SUMMARY_MAPPER =
			(rs, rowNum) -> new ListingSummaryResponse(
					rs.getLong("listing_id"),
					rs.getString("title"),
					rs.getInt("price"),
					rs.getString("status"),
					rs.getString("item_condition"),
					null,
					rs.getInt("wish_count"),
					new UserSummaryResponse(rs.getLong("seller_id"), rs.getString("seller_nickname"),
							rs.getString("seller_profile_image_url")),
					instant(rs, "created_at"));

	private static final RowMapper<ListingDetailResponse> DETAIL_MAPPER =
			(rs, rowNum) -> {
				BigDecimal rating = rs.getBigDecimal("average_rating");
				Long sellerId = rs.getLong("seller_id");
				return new ListingDetailResponse(
						rs.getLong("listing_id"),
						rs.getString("title"),
						rs.getString("description"),
						rs.getInt("price"),
						rs.getString("item_condition"),
						rs.getString("trade_method"),
						rs.getString("status"),
						new CategoryResponse(rs.getLong("category_id"), rs.getString("category_name")),
						List.of(),
						rs.getInt("wish_count"),
						rs.getInt("view_count"),
						rs.getBoolean("is_wished"),
						rs.getBoolean("is_mine"),
						new SellerBriefResponse(sellerId, rs.getString("seller_nickname"),
								rs.getString("seller_profile_image_url"),
								rs.getLong("completed_trade_count"),
								rating == null ? null : rating.doubleValue()),
						instant(rs, "created_at"));
			};

	private final NamedParameterJdbcTemplate jdbc;

	ListingQueryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<ListingSummaryResponse> findSummaries(ListingSearchRequest search, @Nullable ListingCursor cursor,
			@Nullable Long viewerId, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT l.listing_id, l.title, l.price, l.status, l.item_condition,
				       l.wish_count, l.created_at, u.user_id AS seller_id,
				       u.nickname AS seller_nickname,
				       u.profile_image_url AS seller_profile_image_url
				FROM listings l
				JOIN users u ON u.user_id = l.seller_id
				WHERE l.deleted_at IS NULL AND l.status <> 'HIDDEN'
				  AND u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL
				""");
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);

		if (search.keyword() != null) {
			sql.append(" AND (l.title ILIKE :keyword ESCAPE '!' OR l.description ILIKE :keyword ESCAPE '!')");
			params.addValue("keyword", "%" + escapeLike(search.keyword()) + "%");
		}
		if (search.categoryId() != null) {
			sql.append(" AND l.category_id = :categoryId");
			params.addValue("categoryId", search.categoryId());
		}
		if (search.status() != null) {
			sql.append(" AND l.status = :status");
			params.addValue("status", search.status());
		}
		if (search.itemCondition() != null) {
			sql.append(" AND l.item_condition = :itemCondition");
			params.addValue("itemCondition", search.itemCondition());
		}
		if (search.minPrice() != null) {
			sql.append(" AND l.price >= :minPrice");
			params.addValue("minPrice", search.minPrice());
		}
		if (search.maxPrice() != null) {
			sql.append(" AND l.price <= :maxPrice");
			params.addValue("maxPrice", search.maxPrice());
		}
		if (viewerId != null) {
			sql.append(" AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = :viewerId AND b.blocked_id = l.seller_id)");
			params.addValue("viewerId", viewerId);
		}
		if (cursor != null) {
			appendCursorPredicate(sql, search.sort());
			params.addValue("cursorPrice", cursor.price());
			params.addValue("cursorCreatedAt", OffsetDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC),
					Types.TIMESTAMP_WITH_TIMEZONE);
			params.addValue("cursorListingId", cursor.listingId());
		}
		sql.append(switch (search.sort()) {
			case "priceAsc" -> " ORDER BY l.price ASC, l.created_at DESC, l.listing_id DESC";
			case "priceDesc" -> " ORDER BY l.price DESC, l.created_at DESC, l.listing_id DESC";
			default -> " ORDER BY l.created_at DESC, l.listing_id DESC";
		});
		sql.append(" LIMIT :limit");
		return jdbc.query(sql.toString(), params, SUMMARY_MAPPER);
	}

	int incrementViewCount(Long id) {
		return jdbc.update("""
				UPDATE listings SET view_count = view_count + 1
				WHERE listing_id = :listingId AND deleted_at IS NULL AND status <> 'HIDDEN'
				  AND EXISTS (SELECT 1 FROM users u WHERE u.user_id = listings.seller_id
				              AND u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL)
				""", new MapSqlParameterSource("listingId", id));
	}

	Optional<ListingDetailResponse> findDetail(Long id, @Nullable Long viewerId) {
		return findDetail(id, viewerId, false);
	}

	Optional<ListingDetailResponse> findOwnerDetail(Long id, Long ownerId) {
		return findDetail(id, ownerId, true);
	}

	private Optional<ListingDetailResponse> findDetail(Long id, @Nullable Long viewerId, boolean ownerOnly) {
		String sql = """
				SELECT l.listing_id, l.title, l.description, l.price, l.item_condition,
				       l.trade_method, l.status, l.wish_count, l.view_count, l.created_at,
				       c.category_id, c.name AS category_name,
				       u.user_id AS seller_id, u.nickname AS seller_nickname,
				       u.profile_image_url AS seller_profile_image_url,
				       coalesce(l.seller_id = :viewerId, false) AS is_mine,
				       EXISTS (SELECT 1 FROM wishes w
				               WHERE w.listing_id = l.listing_id AND w.user_id = :viewerId) AS is_wished,
				       (SELECT count(*) FROM trades t
				        WHERE t.seller_id = l.seller_id AND t.status = 'COMPLETED') AS completed_trade_count,
				       (SELECT round(avg(r.rating), 1) FROM reviews r
				        WHERE r.reviewee_id = l.seller_id AND r.deleted_at IS NULL) AS average_rating
				FROM listings l
				JOIN users u ON u.user_id = l.seller_id
				JOIN categories c ON c.category_id = l.category_id
				WHERE l.listing_id = :listingId AND l.deleted_at IS NULL
				  AND ((:ownerOnly AND l.seller_id = :viewerId) OR (NOT :ownerOnly AND l.status <> 'HIDDEN'))
				  AND u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL
				""";
		MapSqlParameterSource params = new MapSqlParameterSource("listingId", id)
				.addValue("viewerId", viewerId, Types.BIGINT).addValue("ownerOnly", ownerOnly, Types.BOOLEAN);
		List<ListingDetailResponse> results = jdbc.query(sql, params, DETAIL_MAPPER);
		return results.stream().findFirst();
	}

	private static void appendCursorPredicate(StringBuilder sql, String sort) {
		String latestTail = "(l.created_at < :cursorCreatedAt OR "
				+ "(l.created_at = :cursorCreatedAt AND l.listing_id < :cursorListingId))";
		switch (sort) {
			case "priceAsc" -> sql.append(" AND (l.price > :cursorPrice OR "
					+ "(l.price = :cursorPrice AND " + latestTail + "))");
			case "priceDesc" -> sql.append(" AND (l.price < :cursorPrice OR "
					+ "(l.price = :cursorPrice AND " + latestTail + "))");
			default -> sql.append(" AND ").append(latestTail);
		}
	}

	private static String escapeLike(String keyword) {
		return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getTimestamp(column).toInstant();
	}
}
