package com.reused.listing.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.reused.admin.api.MarketplaceMetrics;
import com.reused.admin.api.MarketplaceMetricsPort;

/** Reads the live marketplace totals for the administrator's dashboard. */
@Repository
public class MarketplaceMetricsAdapter implements MarketplaceMetricsPort {

	private static final String COUNTS_SQL = """
			SELECT (SELECT count(*) FROM listings WHERE deleted_at IS NULL) AS total_listings,
			       (SELECT count(*) FROM listings WHERE deleted_at IS NULL AND status = 'ON_SALE') AS on_sale_listings,
			       (SELECT count(*) FROM trades WHERE status = 'COMPLETED') AS completed_trades""";

	private final JdbcTemplate jdbcTemplate;

	public MarketplaceMetricsAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public MarketplaceMetrics currentMetrics() {
		return jdbcTemplate.queryForObject(COUNTS_SQL,
				(rs, rowNum) -> new MarketplaceMetrics(rs.getLong("total_listings"),
					rs.getLong("on_sale_listings"), rs.getLong("completed_trades")));
	}

}
