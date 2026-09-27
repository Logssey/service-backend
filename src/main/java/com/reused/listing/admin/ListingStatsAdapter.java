package com.reused.listing.admin;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.reused.admin.api.ListingStatsPort;

/** Counts non-deleted listings for each seller in an administrator's page. */
@Repository
public class ListingStatsAdapter implements ListingStatsPort {

	private static final String SELLER_COUNTS_SQL = """
			SELECT seller_id, count(*) AS listing_count
			FROM listings
			WHERE deleted_at IS NULL AND seller_id IN (:sellerIds)
			GROUP BY seller_id""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public ListingStatsAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Map<Long, Long> countBySellers(Collection<Long> sellerIds) {
		if (sellerIds.isEmpty()) {
			return Map.of();
		}
		return jdbcTemplate.query(SELLER_COUNTS_SQL, new MapSqlParameterSource("sellerIds", sellerIds), rs -> {
			Map<Long, Long> counts = new HashMap<>();
			while (rs.next()) {
				counts.put(rs.getLong("seller_id"), rs.getLong("listing_count"));
			}
			return counts;
		});
	}

}
