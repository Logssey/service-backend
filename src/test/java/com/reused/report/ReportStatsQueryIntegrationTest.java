package com.reused.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.report.api.ReportStatsQuery;
import com.reused.report.api.ReportTargetType;

/**
 * 신고 집계. 신고 엔티티가 아직 없으므로 reports 행은 SQL로 넣는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReportStatsQueryIntegrationTest {

	@Autowired
	private ReportStatsQuery reportStatsQuery;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private long reporter;
	private long otherReporter;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE reports, audit_logs, notification_settings, user_status_histories, "
				+ "user_identities, users RESTART IDENTITY CASCADE");
		reporter = insertUser("신고자");
		otherReporter = insertUser("다른신고자");
	}

	@Test
	@DisplayName("대상별 신고 수는 모든 상태를 세고, 요청한 id 중 신고가 없으면 0이며, 다른 유형은 섞이지 않는다")
	void countByTargets() {
		insertReport(reporter, "LISTING", 10, "SPAM", "RECEIVED", null);
		insertReport(otherReporter, "LISTING", 10, "SPAM", "RESOLVED", Instant.now());
		insertReport(reporter, "LISTING", 10, "FALSE_INFO", "REJECTED", Instant.now());
		insertReport(reporter, "LISTING", 11, "SPAM", "IN_REVIEW", null);
		insertReport(reporter, "USER", 10, "OTHER", "RECEIVED", null);

		assertThat(reportStatsQuery.countByTargets(ReportTargetType.LISTING, List.of(10L, 11L, 12L)))
				.containsOnlyKeys(10L, 11L, 12L)
				.containsEntry(10L, 3L)
				.containsEntry(11L, 1L)
				.containsEntry(12L, 0L);
		assertThat(reportStatsQuery.countByTargets(ReportTargetType.USER, List.of(10L))).containsEntry(10L, 1L);
		assertThat(reportStatsQuery.countByTargets(ReportTargetType.COMMUNITY_POST, List.of(10L)))
				.containsEntry(10L, 0L);
	}

	@Test
	@DisplayName("빈 입력·null 입력은 빈 맵이고 null 원소와 중복은 무시한다")
	void emptyAndNullInput() {
		insertReport(reporter, "LISTING", 10, "SPAM", "RECEIVED", null);

		assertThat(reportStatsQuery.countByTargets(ReportTargetType.LISTING, List.of())).isEmpty();
		assertThat(reportStatsQuery.countByTargets(ReportTargetType.LISTING, null)).isEmpty();
		assertThat(reportStatsQuery.countByTargets(ReportTargetType.LISTING, Arrays.asList(10L, null, 10L)))
				.containsOnlyKeys(10L)
				.containsEntry(10L, 1L);
		assertThat(reportStatsQuery.countAgainstUsers(List.of())).isEmpty();
	}

	@Test
	@DisplayName("회원 대상 RESOLVED 신고를 since 이후(경계 포함)만 센다. 반려·미처리·다른 유형·오래된 처리는 빠진다")
	void countResolvedAgainstUserSince() {
		long target = insertUser("피신고자");
		Instant since = Instant.now().minus(Duration.ofDays(90)).truncatedTo(ChronoUnit.MICROS);

		insertReport(reporter, "USER", target, "FRAUD_SUSPICION", "RESOLVED", since.plus(Duration.ofDays(10)));
		insertReport(otherReporter, "USER", target, "FRAUD_SUSPICION", "RESOLVED", since);
		insertReport(reporter, "USER", target, "NO_SHOW", "RESOLVED", since.minusSeconds(1));
		insertReport(reporter, "USER", target, "ABUSIVE_BEHAVIOR", "REJECTED", Instant.now());
		insertReport(reporter, "USER", target, "OTHER", "RECEIVED", null);
		insertReport(reporter, "LISTING", target, "SPAM", "RESOLVED", Instant.now());

		assertThat(reportStatsQuery.countResolvedAgainstUserSince(target, since)).isEqualTo(2);
		assertThat(reportStatsQuery.countResolvedAgainstUserSince(target, Instant.now().plusSeconds(60))).isZero();
		assertThat(reportStatsQuery.countResolvedAgainstUserSince(999L, since)).isZero();
		assertThat(reportStatsQuery.countResolvedAgainstUserSince(null, since)).isZero();
	}

	@Test
	@DisplayName("회원별 피신고 수는 USER 대상만 모든 상태로 센다")
	void countAgainstUsers() {
		long first = insertUser("첫째");
		long second = insertUser("둘째");
		long clean = insertUser("깨끗한회원");
		insertReport(reporter, "USER", first, "OTHER", "RECEIVED", null);
		insertReport(otherReporter, "USER", first, "OTHER", "RESOLVED", Instant.now());
		insertReport(reporter, "USER", second, "NO_SHOW", "REJECTED", Instant.now());
		insertReport(reporter, "LISTING", clean, "SPAM", "RECEIVED", null);

		assertThat(reportStatsQuery.countAgainstUsers(List.of(first, second, clean)))
				.containsEntry(first, 2L)
				.containsEntry(second, 1L)
				.containsEntry(clean, 0L);
	}

	private long insertUser(String nickname) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
	}

	private void insertReport(long reporterId, String targetType, long targetId, String reasonCode, String status,
			Instant handledAt) {
		jdbcTemplate.update("INSERT INTO reports (reporter_id, target_type, target_id, reason_code, status, handled_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?)", reporterId, targetType, targetId, reasonCode, status,
				handledAt == null ? null : Timestamp.from(handledAt));
	}

}
