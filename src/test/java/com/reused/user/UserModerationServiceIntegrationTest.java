package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.RefreshTokenStore;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.api.ActiveUserGuard;
import com.reused.user.entity.UserStatus;
import com.reused.user.service.UserModerationService;
import com.reused.user.service.UserModerationService.ModerationResult;

/**
 * 이용정지·해제 공통 서비스. 상태·이력·감사 기록이 한 트랜잭션인지와 토큰 폐기가 커밋 뒤에만 일어나는지 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserModerationServiceIntegrationTest {

	private static final long MISSING_USER_ID = 999L;
	private static final String REASON = "반복된 욕설";
	private static final Duration DEFAULT_SUSPENSION = Duration.ofDays(7);

	@Autowired
	private UserModerationService moderationService;

	@Autowired
	private ActiveUserGuard activeUserGuard;

	@Autowired
	private RefreshTokenStore refreshTokenStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private long adminId;
	private long targetId;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		adminId = insertUser("관리자");
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", adminId);
		targetId = insertUser("대상회원");
	}

	// --- 관리자 정지 ---

	@Test
	@DisplayName("관리자 정지는 요청한 종료 시각 그대로 저장되고 이력·감사가 같은 트랜잭션에 남는다")
	void suspendByAdminSetsExactUntil() {
		Instant until = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);

		ModerationResult result = moderationService.suspendByAdmin(adminId, targetId, until, REASON);

		assertThat(result).isEqualTo(new ModerationResult(targetId, UserStatus.SUSPENDED, until));
		assertThat(status(targetId)).isEqualTo("SUSPENDED");
		assertThat(suspendedUntil(targetId)).isEqualTo(until);
		assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM users WHERE user_id = ?", Timestamp.class,
				targetId)).isNotNull();

		List<Map<String, Object>> histories = histories();
		assertThat(histories).hasSize(1);
		assertThat(histories.get(0)).containsEntry("user_id", targetId).containsEntry("change_type", "STATUS")
				.containsEntry("before_value", "ACTIVE").containsEntry("after_value", "SUSPENDED")
				.containsEntry("reason", REASON).containsEntry("changed_by", adminId);

		Map<String, Object> audit = singleAudit();
		assertThat(audit).containsEntry("action", "USER_SUSPEND").containsEntry("actor_id", adminId)
				.containsEntry("target_type", "USER").containsEntry("target_id", targetId)
				.containsEntry("result", "SUCCESS");
		JsonNode detail = detail(audit);
		assertThat(detail.get("before").asString()).isEqualTo("ACTIVE");
		assertThat(detail.get("after").asString()).isEqualTo("SUSPENDED");
		assertThat(detail.get("suspendedUntil").asString()).isEqualTo(until.toString());
		assertThat(detail.get("previousSuspendedUntil").isNull()).isTrue();
		assertThat(detail.get("reason").asString()).isEqualTo(REASON);
		assertThat(detail.get("source").asString()).isEqualTo("ADMIN");
		assertThat(detail.has("reportId")).isFalse();
	}

	@Test
	@DisplayName("종료 시각이 없으면 무기한 정지다")
	void suspendByAdminWithoutUntilIsIndefinite() {
		ModerationResult result = moderationService.suspendByAdmin(adminId, targetId, null, REASON);

		assertThat(result).isEqualTo(new ModerationResult(targetId, UserStatus.SUSPENDED, null));
		assertThat(status(targetId)).isEqualTo("SUSPENDED");
		assertThat(suspendedUntil(targetId)).isNull();
		assertThat(detail(singleAudit()).get("suspendedUntil").isNull()).isTrue();
	}

	@Test
	@DisplayName("이미 정지 중이면 기간만 바꾸고 SUSPENDED→SUSPENDED 이력과 이전 종료 시각을 남긴다")
	void suspendByAdminChangesPeriodOfSuspendedUser() {
		Instant previous = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
		suspendDirectly(targetId, previous);
		Instant until = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);

		moderationService.suspendByAdmin(adminId, targetId, until, REASON);

		assertThat(suspendedUntil(targetId)).isEqualTo(until);
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "SUSPENDED"));
		JsonNode detail = detail(singleAudit());
		assertThat(detail.get("before").asString()).isEqualTo("SUSPENDED");
		assertThat(detail.get("previousSuspendedUntil").asString()).isEqualTo(previous.toString());
	}

	@Test
	@DisplayName("종료 시각이 현재 이전이면 400이고 아무것도 바뀌지 않는다")
	void pastUntilIsRejected() {
		Instant past = Instant.now().minus(Duration.ofMinutes(1));

		assertThatThrownBy(() -> moderationService.suspendByAdmin(adminId, targetId, past, REASON))
				.satisfies(hasCode(ErrorCode.INVALID_INPUT));

		assertUnchanged();
	}

	@Test
	@DisplayName("사유가 없거나 공백이거나 500자를 넘으면 400이다(이력 사유는 NOT NULL)")
	void reasonIsRequired() {
		assertThatThrownBy(() -> moderationService.suspendByAdmin(adminId, targetId, null, null))
				.satisfies(hasCode(ErrorCode.INVALID_INPUT));
		assertThatThrownBy(() -> moderationService.suspendForReport(adminId, targetId, "  ", 1L))
				.satisfies(hasCode(ErrorCode.INVALID_INPUT));
		assertThatThrownBy(() -> moderationService.activate(adminId, targetId, "가".repeat(501)))
				.satisfies(hasCode(ErrorCode.INVALID_INPUT));

		assertUnchanged();
	}

	@Test
	@DisplayName("본인 대상은 403 FORBIDDEN이다")
	void selfIsForbidden() {
		assertThatThrownBy(() -> moderationService.suspendByAdmin(adminId, adminId, null, REASON))
				.satisfies(hasCode(ErrorCode.FORBIDDEN));
		assertThatThrownBy(() -> moderationService.suspendForReport(adminId, adminId, REASON, 1L))
				.satisfies(hasCode(ErrorCode.FORBIDDEN));
		assertThatThrownBy(() -> moderationService.activate(adminId, adminId, REASON))
				.satisfies(hasCode(ErrorCode.FORBIDDEN));

		assertThat(status(adminId)).isEqualTo("ACTIVE");
		assertThat(histories()).isEmpty();
	}

	@Test
	@DisplayName("없는 대상은 404 NOT_FOUND다")
	void missingTargetIsNotFound() {
		assertThatThrownBy(() -> moderationService.suspendByAdmin(adminId, MISSING_USER_ID, null, REASON))
				.satisfies(hasCode(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> moderationService.suspendForReport(adminId, MISSING_USER_ID, REASON, 1L))
				.satisfies(hasCode(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> moderationService.activate(adminId, MISSING_USER_ID, REASON))
				.satisfies(hasCode(ErrorCode.NOT_FOUND));
	}

	@Test
	@DisplayName("탈퇴한 대상은 409 CONFLICT이고 상태가 바뀌지 않는다")
	void withdrawnTargetIsConflict() {
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", targetId);

		assertThatThrownBy(() -> moderationService.suspendByAdmin(adminId, targetId, null, REASON))
				.satisfies(hasCode(ErrorCode.CONFLICT));
		assertThatThrownBy(() -> moderationService.suspendForReport(adminId, targetId, REASON, 1L))
				.satisfies(hasCode(ErrorCode.CONFLICT));
		assertThatThrownBy(() -> moderationService.activate(adminId, targetId, REASON))
				.satisfies(hasCode(ErrorCode.CONFLICT));

		assertThat(status(targetId)).isEqualTo("WITHDRAWN");
		assertThat(histories()).isEmpty();
		assertThat(auditCount()).isZero();
	}

	// --- 신고 처리 정지 ---

	@Test
	@DisplayName("신고 정지는 기본 7일이고 감사 detail에 source=REPORT와 reportId가 남는다")
	void suspendForReportAppliesDefaultPeriod() {
		Instant before = Instant.now();

		ModerationResult result = moderationService.suspendForReport(adminId, targetId, REASON, 30L);

		Instant until = suspendedUntil(targetId);
		assertThat(until).isBetween(before.plus(DEFAULT_SUSPENSION).minusSeconds(1),
				Instant.now().plus(DEFAULT_SUSPENSION).plusSeconds(1));
		assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
		assertThat(result.suspendedUntil()).isCloseTo(until, within(1, ChronoUnit.MILLIS));
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "ACTIVE").containsEntry("after_value", "SUSPENDED")
				.containsEntry("reason", REASON).containsEntry("changed_by", adminId));
		JsonNode detail = detail(singleAudit());
		assertThat(detail.get("source").asString()).isEqualTo("REPORT");
		assertThat(detail.get("reportId").asLong()).isEqualTo(30L);
	}

	@Test
	@DisplayName("이미 더 긴 정지 중이면 줄이지 않는다. 기간이 그대로면 이력은 없고 감사는 남는다")
	void suspendForReportKeepsLongerSuspension() {
		Instant longer = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
		suspendDirectly(targetId, longer);

		ModerationResult result = moderationService.suspendForReport(adminId, targetId, REASON, 30L);

		assertThat(result.suspendedUntil()).isEqualTo(longer);
		assertThat(suspendedUntil(targetId)).isEqualTo(longer);
		assertThat(histories()).isEmpty();
		JsonNode detail = detail(singleAudit());
		assertThat(detail.get("suspendedUntil").asString()).isEqualTo(longer.toString());
		assertThat(detail.get("previousSuspendedUntil").asString()).isEqualTo(longer.toString());
	}

	@Test
	@DisplayName("남은 정지가 7일보다 짧으면 지금부터 7일로 늘린다")
	void suspendForReportExtendsShorterSuspension() {
		suspendDirectly(targetId, Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS));
		Instant before = Instant.now();

		moderationService.suspendForReport(adminId, targetId, REASON, 30L);

		assertThat(suspendedUntil(targetId)).isAfterOrEqualTo(before.plus(DEFAULT_SUSPENSION).minusSeconds(1));
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "SUSPENDED"));
	}

	@Test
	@DisplayName("무기한 정지는 무기한으로 남는다")
	void suspendForReportKeepsIndefiniteSuspension() {
		suspendDirectly(targetId, null);

		ModerationResult result = moderationService.suspendForReport(adminId, targetId, REASON, 30L);

		assertThat(result.suspendedUntil()).isNull();
		assertThat(status(targetId)).isEqualTo("SUSPENDED");
		assertThat(suspendedUntil(targetId)).isNull();
		assertThat(histories()).isEmpty();
	}

	@Test
	@DisplayName("기간이 지난 정지는 정지 중이 아닌 것으로 보고 지금부터 7일을 준다")
	void suspendForReportRestartsExpiredSuspension() {
		suspendDirectly(targetId, Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS));
		Instant before = Instant.now();

		moderationService.suspendForReport(adminId, targetId, REASON, 30L);

		assertThat(suspendedUntil(targetId)).isAfterOrEqualTo(before.plus(DEFAULT_SUSPENSION).minusSeconds(1));
	}

	// --- 해제 ---

	@Test
	@DisplayName("해제하면 ACTIVE가 되고 종료 시각이 지워지며 이력과 USER_ACTIVATE 감사가 남는다")
	void activateReleasesSuspension() {
		Instant previous = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);
		suspendDirectly(targetId, previous);

		ModerationResult result = moderationService.activate(adminId, targetId, "소명 확인");

		assertThat(result).isEqualTo(new ModerationResult(targetId, UserStatus.ACTIVE, null));
		assertThat(status(targetId)).isEqualTo("ACTIVE");
		assertThat(suspendedUntil(targetId)).isNull();
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "ACTIVE")
				.containsEntry("reason", "소명 확인").containsEntry("changed_by", adminId));
		Map<String, Object> audit = singleAudit();
		assertThat(audit).containsEntry("action", "USER_ACTIVATE").containsEntry("actor_id", adminId)
				.containsEntry("target_id", targetId);
		JsonNode detail = detail(audit);
		assertThat(detail.get("after").asString()).isEqualTo("ACTIVE");
		assertThat(detail.get("suspendedUntil").isNull()).isTrue();
		assertThat(detail.get("previousSuspendedUntil").asString()).isEqualTo(previous.toString());
		assertThat(detail.get("source").asString()).isEqualTo("ADMIN");
	}

	@Test
	@DisplayName("기간이 지났지만 상태값이 SUSPENDED인 회원도 해제할 수 있다")
	void activateExpiredSuspension() {
		suspendDirectly(targetId, Instant.now().minus(Duration.ofMinutes(1)));

		moderationService.activate(adminId, targetId, REASON);

		assertThat(status(targetId)).isEqualTo("ACTIVE");
		assertThat(histories()).hasSize(1);
	}

	@Test
	@DisplayName("ACTIVE 회원을 해제하면 409 CONFLICT다")
	void activateActiveUserIsConflict() {
		assertThatThrownBy(() -> moderationService.activate(adminId, targetId, REASON))
				.satisfies(hasCode(ErrorCode.CONFLICT));

		assertUnchanged();
	}

	@Test
	@DisplayName("해제는 Refresh Token을 폐기하지 않는다")
	void activateKeepsRefreshTokens() {
		suspendDirectly(targetId, null);
		refreshTokenStore.issue(targetId);

		moderationService.activate(adminId, targetId, REASON);

		assertThat(refreshKeys(targetId)).hasSize(1);
	}

	// --- 트랜잭션 경계 ---

	@Test
	@DisplayName("정지하면 대상의 Refresh Token이 커밋 뒤에만 전부 폐기된다. 다른 회원의 토큰은 남는다")
	void refreshTokensAreRevokedAfterCommit() {
		refreshTokenStore.issue(targetId);
		refreshTokenStore.issue(targetId);
		refreshTokenStore.issue(adminId);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			moderationService.suspendForReport(adminId, targetId, REASON, 30L);
			assertThat(refreshKeys(targetId)).as("커밋 전에는 남아 있다").hasSize(2);
		});

		assertThat(refreshKeys(targetId)).isEmpty();
		assertThat(refreshKeys(adminId)).hasSize(1);
	}

	@Test
	@DisplayName("같은 트랜잭션에서 먼저 읽은 회원도 잠근 뒤의 값으로 판단한다. 그사이 걸린 무기한 정지를 7일로 줄이지 않는다")
	void suspendForReportSeesSuspensionCommittedBeforeLock() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			// 신고 처리처럼 대상을 먼저 읽는 호출자. 대상이 ACTIVE로 영속성 컨텍스트에 올라온다.
			activeUserGuard.requireMember(targetId);
			commitInOtherTransaction(
					"UPDATE users SET status = 'SUSPENDED', suspended_until = NULL WHERE user_id = ?", targetId);

			ModerationResult result = moderationService.suspendForReport(adminId, targetId, REASON, 30L);

			assertThat(result.suspendedUntil()).isNull();
		});

		assertThat(status(targetId)).isEqualTo("SUSPENDED");
		assertThat(suspendedUntil(targetId)).isNull();
		assertThat(histories()).isEmpty();
		JsonNode detail = detail(singleAudit());
		assertThat(detail.get("before").asString()).isEqualTo("SUSPENDED");
		assertThat(detail.get("suspendedUntil").isNull()).isTrue();
	}

	@Test
	@DisplayName("같은 트랜잭션에서 먼저 읽은 회원도 잠근 뒤의 값으로 판단한다. 그사이 정지된 회원은 해제할 수 있다")
	void activateSeesSuspensionCommittedBeforeLock() {
		Instant until = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			activeUserGuard.requireMember(targetId);
			commitInOtherTransaction("UPDATE users SET status = 'SUSPENDED', suspended_until = ? WHERE user_id = ?",
					Timestamp.from(until), targetId);

			moderationService.activate(adminId, targetId, REASON);
		});

		assertThat(status(targetId)).isEqualTo("ACTIVE");
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "ACTIVE"));
		assertThat(detail(singleAudit()).get("previousSuspendedUntil").asString()).isEqualTo(until.toString());
	}

	@Test
	@DisplayName("호출자 트랜잭션이 롤백되면 상태·이력·감사가 모두 사라지고 토큰도 폐기되지 않는다")
	void rollbackUndoesEverything() {
		refreshTokenStore.issue(targetId);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			moderationService.suspendByAdmin(adminId, targetId, null, REASON);
			status.setRollbackOnly();
		});

		assertUnchanged();
		assertThat(refreshKeys(targetId)).hasSize(1);
	}

	@Test
	@DisplayName("감사 기록이 실패하면 정지도 롤백된다(기록 없는 관리자 변경을 남기지 않는다)")
	void auditFailureRollsBackSuspension() {
		refreshTokenStore.issue(targetId);
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			assertThatThrownBy(() -> moderationService.suspendByAdmin(adminId, targetId, null, REASON))
					.isInstanceOf(DataAccessException.class);
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}

		assertUnchanged();
		assertThat(refreshKeys(targetId)).hasSize(1);
	}

	// --- helpers ---

	private long insertUser(String nickname) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
	}

	/**
	 * 테스트 스레드에 묶인 트랜잭션과 다른 커넥션으로 바로 커밋한다. 다른 관리자의 동시 변경을 흉내 낸다.
	 */
	private void commitInOtherTransaction(String sql, Object... args) {
		CompletableFuture.runAsync(() -> jdbcTemplate.update(sql, args)).join();
	}

	private void suspendDirectly(long userId, Instant until) {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = ? WHERE user_id = ?",
				until == null ? null : Timestamp.from(until), userId);
	}

	private String status(long userId) {
		return jdbcTemplate.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, userId);
	}

	private Instant suspendedUntil(long userId) {
		Timestamp until = jdbcTemplate.queryForObject("SELECT suspended_until FROM users WHERE user_id = ?",
				Timestamp.class, userId);
		return until == null ? null : until.toInstant();
	}

	private List<Map<String, Object>> histories() {
		return jdbcTemplate.queryForList("SELECT user_id, change_type, before_value, after_value, reason, changed_by "
				+ "FROM user_status_histories ORDER BY history_id");
	}

	private Map<String, Object> singleAudit() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT action, actor_id, target_type, target_id, result, detail::text AS detail FROM audit_logs");
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private long auditCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
	}

	private JsonNode detail(Map<String, Object> audit) {
		return objectMapper.readTree((String) audit.get("detail"));
	}

	private Set<String> refreshKeys(long userId) {
		return redisTemplate.keys("reused:auth:refresh:" + userId + ":*");
	}

	/** 대상이 ACTIVE 그대로이고 이력·감사가 없다 */
	private void assertUnchanged() {
		assertThat(status(targetId)).isEqualTo("ACTIVE");
		assertThat(suspendedUntil(targetId)).isNull();
		assertThat(histories()).isEmpty();
		assertThat(auditCount()).isZero();
	}

	private static ThrowingConsumer<Throwable> hasCode(ErrorCode code) {
		return thrown -> assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.errorCode()).isEqualTo(code));
	}

}
