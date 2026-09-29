package com.reused.admin.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.admin.api.MarketplaceMetrics;
import com.reused.admin.api.MarketplaceMetricsPort;
import com.reused.admin.dto.response.AdminDashboardResponse;

/**
 * 관리자 대시보드. 회원·신고 수치는 B 소유 테이블(users, reports)을 SQL로 직접 세고,
 * 게시글·거래 수치는 A의 {@link MarketplaceMetricsPort}에서 받는다. 구현이 없으면 0이다.
 *
 * <p>캐시하지 않는다. redis-keys.md에 대시보드 키가 없고, 관리자 트래픽은 적다.
 */
@Service
public class AdminDashboardService {

	/**
	 * 탈퇴 시각만 기록된 비정상 행도 탈퇴로 센다({@code User.isWithdrawn()}과 같은 기준).
	 * 정지 수는 DB 상태값이다. 기간이 끝난 정지는 {@code SuspensionExpiryJob}이 주기적으로 ACTIVE로 되돌린다.
	 */
	private static final String USER_COUNTS_SQL = """
			SELECT count(*) AS total,
			       count(*) FILTER (WHERE status = 'ACTIVE' AND withdrawn_at IS NULL) AS active,
			       count(*) FILTER (WHERE status = 'SUSPENDED' AND withdrawn_at IS NULL) AS suspended
			FROM users""";

	/** DDL 주석이 "미처리 상태"라 부르는 두 상태(uq_reports_pending_duplicate) */
	private static final String PENDING_REPORTS_SQL = """
			SELECT count(*) FROM reports WHERE status IN ('RECEIVED', 'IN_REVIEW')""";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectProvider<MarketplaceMetricsPort> marketplaceMetricsPort;

	public AdminDashboardService(JdbcTemplate jdbcTemplate,
			ObjectProvider<MarketplaceMetricsPort> marketplaceMetricsPort) {
		this.jdbcTemplate = jdbcTemplate;
		this.marketplaceMetricsPort = marketplaceMetricsPort;
	}

	@Transactional(readOnly = true)
	public AdminDashboardResponse getDashboard() {
		UserCounts users = jdbcTemplate.queryForObject(USER_COUNTS_SQL, (rs, rowNum) -> new UserCounts(
				rs.getLong("total"), rs.getLong("active"), rs.getLong("suspended")));
		Long pendingReports = jdbcTemplate.queryForObject(PENDING_REPORTS_SQL, Long.class);
		MarketplaceMetrics marketplace = marketplaceMetrics();
		return new AdminDashboardResponse(
				users.total(),
				users.active(),
				users.suspended(),
				marketplace.totalListings(),
				marketplace.onSaleListings(),
				marketplace.completedTrades(),
				pendingReports == null ? 0L : pendingReports);
	}

	/** A 코드가 없으면 게시글·거래 데이터도 없다 */
	private MarketplaceMetrics marketplaceMetrics() {
		MarketplaceMetricsPort port = marketplaceMetricsPort.getIfAvailable();
		return port == null ? MarketplaceMetrics.empty() : port.currentMetrics();
	}

	private record UserCounts(long total, long active, long suspended) {
	}

}
