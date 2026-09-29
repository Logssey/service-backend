package com.reused.user.admin;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;
import com.reused.user.admin.dto.request.AdminUserStatusChange;
import com.reused.user.admin.service.AdminUserService;
import com.reused.user.entity.UserRole;

/**
 * PATCH /api/v1/admin/users/{userId}/role. 역할·이력·감사가 한 트랜잭션이고 회수는 커밋 뒤 Refresh Token을 폐기하는지 본다.
 * 관리자 권한 규칙(401·403)은 {@code AdminEndpointAccessIntegrationTest}가 함께 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserRoleIntegrationTest {

	private static final String REASON = "운영팀 합류";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private AdminUserService adminUserService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private AdminTestClient client;
	private Member admin;
	private Member target;
	private Member other;

	@BeforeEach
	void resetState() throws Exception {
		client = new AdminTestClient(mockMvc, objectMapper, jdbcTemplate, redisTemplate);
		client.reset();
		admin = client.signupAdmin("admin@example.com", "관리자");
		target = client.signup("target@example.com", "대상회원");
		other = client.signup("other@example.com", "다른회원");
		// 가입이 남긴 AUTH_SIGNUP은 이 테스트와 무관하다.
		jdbcTemplate.update("DELETE FROM audit_logs");
	}

	// --- 부여 ---

	@Test
	@DisplayName("ADMIN 부여: 200으로 userId·role을 주고 역할·이력·USER_ROLE_GRANT 감사가 남는다. 토큰은 폐기하지 않는다")
	void grantAdmin() throws Exception {
		changeRole(target.userId(), "ADMIN", REASON)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(target.userId()))
				.andExpect(jsonPath("$.role").value("ADMIN"));

		assertThat(role(target.userId())).isEqualTo("ADMIN");
		assertThat(jdbcTemplate.queryForObject("SELECT updated_at IS NOT NULL FROM users WHERE user_id = ?",
				Boolean.class, target.userId())).isTrue();
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("user_id", target.userId()).containsEntry("change_type", "ROLE")
				.containsEntry("before_value", "USER").containsEntry("after_value", "ADMIN")
				.containsEntry("reason", REASON).containsEntry("changed_by", admin.userId()));
		Map<String, Object> audit = singleAudit();
		assertThat(audit).containsEntry("action", "USER_ROLE_GRANT").containsEntry("actor_id", admin.userId())
				.containsEntry("target_type", "USER").containsEntry("target_id", target.userId())
				.containsEntry("result", "SUCCESS");
		assertThat(audit.get("ip_address")).isNotNull();
		JsonNode detail = detail(audit);
		assertThat(detail.get("before").asString()).isEqualTo("USER");
		assertThat(detail.get("after").asString()).isEqualTo("ADMIN");
		assertThat(detail.get("reason").asString()).isEqualTo(REASON);
		assertThat(client.refreshKeys(target.userId())).hasSize(1);
		// 계정 알림 유형이 없어 역할 변경은 알림을 만들지 않는다.
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
	}

	@Test
	@DisplayName("부여받은 회원은 재발급한 토큰으로 관리자 API를 쓸 수 있다")
	void grantedAdminCanUseAdminApiAfterRefresh() throws Exception {
		changeRole(target.userId(), "ADMIN", REASON).andExpect(status().isOk());

		Member refreshed = client.refresh(target);

		mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(refreshed)))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("사유는 앞뒤 공백을 떼고 이력과 감사에 저장한다")
	void reasonIsTrimmed() throws Exception {
		changeRole(target.userId(), "ADMIN", "  운영팀 합류  ").andExpect(status().isOk());

		assertThat(histories().get(0)).containsEntry("reason", REASON);
		assertThat(detail(singleAudit()).get("reason").asString()).isEqualTo(REASON);
	}

	@Test
	@DisplayName("사유는 500자까지 받는다")
	void reasonOf500CharsIsAccepted() throws Exception {
		changeRole(target.userId(), "ADMIN", "가".repeat(500)).andExpect(status().isOk());

		assertThat(histories().get(0).get("reason")).isEqualTo("가".repeat(500));
	}

	@Test
	@DisplayName("정지 기간이 이미 지난 회원은 정지 중이 아니므로 ADMIN을 부여할 수 있다")
	void grantToExpiredSuspension() throws Exception {
		client.suspend(target.userId(), "now() - interval '1 minute'");

		changeRole(target.userId(), "ADMIN", REASON).andExpect(status().isOk());

		assertThat(role(target.userId())).isEqualTo("ADMIN");
	}

	// --- 회수 ---

	@Test
	@DisplayName("ADMIN 회수: USER_ROLE_REVOKE 감사와 이력이 남고, 커밋 뒤 대상의 Refresh Token만 전부 폐기된다")
	void revokeAdmin() throws Exception {
		Member targetAdmin = promote(target);

		changeRole(target.userId(), "USER", "업무 종료")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(target.userId()))
				.andExpect(jsonPath("$.role").value("USER"));

		assertThat(role(target.userId())).isEqualTo("USER");
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("change_type", "ROLE").containsEntry("before_value", "ADMIN")
				.containsEntry("after_value", "USER").containsEntry("reason", "업무 종료"));
		Map<String, Object> audit = singleAudit();
		assertThat(audit).containsEntry("action", "USER_ROLE_REVOKE").containsEntry("target_id", target.userId());
		assertThat(detail(audit).get("before").asString()).isEqualTo("ADMIN");
		assertThat(detail(audit).get("after").asString()).isEqualTo("USER");

		assertThat(client.refreshKeys(target.userId())).isEmpty();
		assertThat(client.refreshKeys(admin.userId())).hasSize(1);
		assertThat(client.refreshKeys(other.userId())).hasSize(1);
		// 남은 ADMIN Access Token은 DB 재확인이 막고, 재발급은 폐기된 토큰이라 401이다.
		mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(targetAdmin)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(AdminTestClient.REFRESH_COOKIE, targetAdmin.refreshToken())))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("정지 중인 관리자의 역할도 회수할 수 있다(부여만 막는다)")
	void revokeFromSuspendedAdmin() throws Exception {
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", target.userId());
		client.suspend(target.userId(), "NULL");

		changeRole(target.userId(), "USER", "업무 종료").andExpect(status().isOk());

		assertThat(role(target.userId())).isEqualTo("USER");
	}

	// --- 오류 ---

	@Test
	@DisplayName("본인 대상은 403 FORBIDDEN이고 아무것도 바뀌지 않는다(명세 오류 표)")
	void selfIsForbidden() throws Exception {
		changeRole(admin.userId(), "USER", REASON)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("본인의 역할은 변경할 수 없습니다."));

		assertThat(role(admin.userId())).isEqualTo("ADMIN");
		assertNothingRecorded();
		assertThat(client.refreshKeys(admin.userId())).hasSize(1);
	}

	@Test
	@DisplayName("없는 회원은 404 NOT_FOUND다")
	void missingTargetIsNotFound() throws Exception {
		changeRole(999L, "ADMIN", REASON)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		assertNothingRecorded();
	}

	@Test
	@DisplayName("탈퇴한 회원은 409 CONFLICT이고 역할이 바뀌지 않는다")
	void withdrawnTargetIsConflict() throws Exception {
		client.withdraw(target.userId());

		changeRole(target.userId(), "ADMIN", REASON)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		assertThat(role(target.userId())).isEqualTo("USER");
		assertNothingRecorded();
	}

	@Test
	@DisplayName("이미 같은 역할이면 409 CONFLICT다(USER→USER, ADMIN→ADMIN)")
	void sameRoleIsConflict() throws Exception {
		changeRole(target.userId(), "USER", REASON)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", other.userId());
		changeRole(other.userId(), "ADMIN", REASON)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		assertNothingRecorded();
		assertThat(client.refreshKeys(other.userId())).hasSize(1);
	}

	@ParameterizedTest(name = "suspended_until = {0}")
	@ValueSource(strings = { "NULL", "now() + interval '1 day'" })
	@DisplayName("이용정지 중인 회원(무기한 또는 기간 남음)에게 ADMIN을 부여하면 409 CONFLICT다")
	void grantToSuspendedIsConflict(String suspendedUntil) throws Exception {
		client.suspend(target.userId(), suspendedUntil);

		changeRole(target.userId(), "ADMIN", REASON)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		assertThat(role(target.userId())).isEqualTo("USER");
		assertNothingRecorded();
	}

	static Stream<Arguments> invalidBodies() {
		Map<String, Object> missingRole = new HashMap<>();
		missingRole.put("reason", REASON);
		Map<String, Object> nullRole = new HashMap<>(missingRole);
		nullRole.put("role", null);
		Map<String, Object> missingReason = new HashMap<>();
		missingReason.put("role", "ADMIN");
		Map<String, Object> nullReason = new HashMap<>(missingReason);
		nullReason.put("reason", null);
		return Stream.of(
				Arguments.of("role 누락", missingRole),
				Arguments.of("role null", nullRole),
				Arguments.of("role이 USER·ADMIN 밖의 값", Map.of("role", "SUPER", "reason", REASON)),
				Arguments.of("role 소문자", Map.of("role", "admin", "reason", REASON)),
				Arguments.of("reason 누락", missingReason),
				Arguments.of("reason null", nullReason),
				Arguments.of("reason 빈 문자열", Map.of("role", "ADMIN", "reason", "")),
				Arguments.of("reason 공백뿐", Map.of("role", "ADMIN", "reason", "   ")),
				Arguments.of("reason 501자", Map.of("role", "ADMIN", "reason", "가".repeat(501))));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	@DisplayName("요청 본문 검증 실패는 400 INVALID_INPUT이고 아무것도 바뀌지 않는다")
	void invalidBodyIsRejected(String name, Map<String, Object> body) throws Exception {
		mockMvc.perform(client.json(patch(rolePath(target.userId())), body).header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		assertThat(role(target.userId())).isEqualTo("USER");
		assertNothingRecorded();
	}

	@Test
	@DisplayName("깨진 JSON과 숫자가 아닌 경로 변수는 400이다")
	void malformedRequestIsRejected() throws Exception {
		mockMvc.perform(patch(rolePath(target.userId())).header("Authorization", bearer(admin))
						.contentType(MediaType.APPLICATION_JSON).content("{\"role\":"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(client.json(patch("/api/v1/admin/users/abc/role"), Map.of("role", "ADMIN", "reason", REASON))
						.header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("userId: 값의 형식이 올바르지 않습니다."));

		assertNothingRecorded();
	}

	// --- 트랜잭션 ---

	@Test
	@DisplayName("감사 기록이 실패하면 역할 변경·이력이 롤백되고 토큰도 폐기되지 않는다")
	void auditFailureRollsBack() throws Exception {
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", target.userId());
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			changeRole(target.userId(), "USER", REASON)
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}

		assertThat(role(target.userId())).isEqualTo("ADMIN");
		assertNothingRecorded();
		assertThat(client.refreshKeys(target.userId())).hasSize(1);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "role = 'USER'", "status = 'SUSPENDED', suspended_until = NULL",
			"status = 'SUSPENDED', suspended_until = now() + interval '1 day'" })
	@DisplayName("요청자가 잠금 시점에 역할이 회수되었거나 정지 중이면 403이다(요청 검사 뒤 동시에 회수·정지된 경우)")
	void requesterIsRecheckedUnderLock(String requesterChange) {
		// 인터셉터를 통과한 뒤 다른 관리자가 먼저 커밋한 상황. 서비스를 직접 불러 잠금 뒤 재확인만 본다.
		jdbcTemplate.update("UPDATE users SET " + requesterChange + " WHERE user_id = ?", admin.userId());

		assertRequesterRejectedUnderLock(admin.userId(), ErrorCode.FORBIDDEN);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "status = 'WITHDRAWN', withdrawn_at = now()", "withdrawn_at = now()",
			"status = 'SUSPENDED', suspended_until = NULL, withdrawn_at = now()" })
	@DisplayName("요청자가 잠금 시점에 탈퇴했으면 인터셉터와 같이 401 UNAUTHENTICATED다(탈퇴 토큰 규칙, contracts §1.5)")
	void withdrawnRequesterIsUnauthenticatedUnderLock(String requesterChange) {
		jdbcTemplate.update("UPDATE users SET " + requesterChange + " WHERE user_id = ?", admin.userId());

		assertRequesterRejectedUnderLock(admin.userId(), ErrorCode.UNAUTHENTICATED);
	}

	@Test
	@DisplayName("요청자 행이 잠금 시점에 없으면 401 UNAUTHENTICATED다")
	void missingRequesterIsUnauthenticatedUnderLock() {
		assertRequesterRejectedUnderLock(999L, ErrorCode.UNAUTHENTICATED);
	}

	@Test
	@DisplayName("두 관리자가 동시에 서로의 역할을 회수하면 먼저 커밋한 쪽만 성공하고 늦은 쪽은 403이다(관리자 0명 방지)")
	void concurrentMutualRevocationKeepsOneAdmin() throws Exception {
		Member second = client.signupAdmin("second@example.com", "둘째관리자");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// 둘째 관리자 행을 잡아 첫 요청이 (관리자 행을 잠근 채) 거기서 기다리게 한다.
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					jdbcTemplate.queryForList("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE",
							second.userId());
					locked.countDown();
					awaitQuietly(release);
				}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<Integer> adminRevokesSecond = CompletableFuture.supplyAsync(
				() -> patchRole(admin, second.userId(), "USER"));
		awaitLockWaiters(1);
		CompletableFuture<Integer> secondRevokesAdmin = CompletableFuture.supplyAsync(
				() -> patchRole(second, admin.userId(), "USER"));
		awaitLockWaiters(2);
		release.countDown();
		holder.join();

		assertThat(adminRevokesSecond.get(10, TimeUnit.SECONDS)).isEqualTo(200);
		assertThat(secondRevokesAdmin.get(10, TimeUnit.SECONDS)).isEqualTo(403);
		assertThat(role(admin.userId())).isEqualTo("ADMIN");
		assertThat(role(second.userId())).isEqualTo("USER");
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("user_id", second.userId()).containsEntry("changed_by", admin.userId()));
	}

	@Test
	@DisplayName("요청자보다 id가 작은 대상도 같은 규칙으로 처리한다(잠금 순서만 다르다)")
	void targetWithSmallerIdThanRequester() throws Exception {
		Member laterAdmin = client.signupAdmin("later@example.com", "나중관리자");

		mockMvc.perform(client.json(patch(rolePath(target.userId())), Map.of("role", "ADMIN", "reason", REASON))
						.header("Authorization", bearer(laterAdmin)))
				.andExpect(status().isOk());

		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("changed_by", laterAdmin.userId()));
	}

	// --- helpers ---

	private ResultActions changeRole(long userId, String role, String reason) throws Exception {
		return mockMvc.perform(client.json(patch(rolePath(userId)), Map.of("role", role, "reason", reason))
				.header("Authorization", bearer(admin)));
	}

	private static String rolePath(long userId) {
		return "/api/v1/admin/users/" + userId + "/role";
	}

	/** 다른 스레드에서 부른다. 응답 상태 코드만 돌려준다 */
	private int patchRole(Member caller, long targetId, String role) {
		try {
			return mockMvc.perform(client.json(patch(rolePath(targetId)), Map.of("role", role, "reason", REASON))
							.header("Authorization", bearer(caller)))
					.andReturn().getResponse().getStatus();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 행 잠금을 기다리는 세션이 {@code waiters}개 이상이 될 때까지 기다린다 */
	private void awaitLockWaiters(int waiters) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Long waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
					+ "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
			if (waiting != null && waiting >= waiters) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("잠금 대기 세션이 " + waiters + "개가 되지 않았다");
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** DB에서 ADMIN으로 올리고 재발급해 ADMIN 클레임 토큰을 받는다 */
	private Member promote(Member member) throws Exception {
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", member.userId());
		return client.refresh(member);
	}

	private String role(long userId) {
		return jdbcTemplate.queryForObject("SELECT role FROM users WHERE user_id = ?", String.class, userId);
	}

	private List<Map<String, Object>> histories() {
		return jdbcTemplate.queryForList("SELECT user_id, change_type, before_value, after_value, reason, changed_by "
				+ "FROM user_status_histories ORDER BY history_id");
	}

	private Map<String, Object> singleAudit() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT action, actor_id, target_type, target_id, "
				+ "result, host(ip_address) AS ip_address, detail::text AS detail FROM audit_logs");
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private JsonNode detail(Map<String, Object> audit) {
		return objectMapper.readTree((String) audit.get("detail"));
	}

	private void assertNothingRecorded() {
		assertThat(histories()).isEmpty();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
	}

	/** 역할·상태 변경 둘 다 잠금 뒤 요청자 재확인에서 {@code expected}로 막히고 대상은 그대로다 */
	private void assertRequesterRejectedUnderLock(long requesterId, ErrorCode expected) {
		assertThatThrownBy(() -> adminUserService.changeRole(requesterId, target.userId(), UserRole.ADMIN, REASON))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.errorCode()).isEqualTo(expected));
		assertThatThrownBy(() -> adminUserService.changeStatus(requesterId, target.userId(),
				AdminUserStatusChange.SUSPENDED, null, REASON))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.errorCode()).isEqualTo(expected));

		assertThat(role(target.userId())).isEqualTo("USER");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class,
				target.userId())).isEqualTo("ACTIVE");
		assertNothingRecorded();
	}

}
