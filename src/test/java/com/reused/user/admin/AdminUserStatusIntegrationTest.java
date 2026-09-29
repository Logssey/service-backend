package com.reused.user.admin;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

/**
 * PATCH /api/v1/admin/users/{userId}/status. 정지·해제 규칙 자체는 {@code UserModerationServiceIntegrationTest}가 보고,
 * 여기서는 HTTP 계약(요청 검증, 응답, 오류 코드)과 부수효과(이력, 감사, Refresh Token 폐기)를 끝까지 확인한다.
 * 관리자 권한 규칙(401·403)은 {@code AdminEndpointAccessIntegrationTest}가 함께 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserStatusIntegrationTest {

	private static final String REASON = "반복적인 허위 매물 등록";
	private static final String PASSWORD = "hunter22!pw";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

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

	// --- 정지 ---

	@Test
	@DisplayName("기간 정지: 200으로 상태·종료 시각을 주고 이력·USER_SUSPEND 감사가 남으며 대상의 Refresh Token만 폐기된다")
	void suspendWithPeriod() throws Exception {
		Instant until = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS);

		changeStatus(target.userId(), body("SUSPENDED", until.toString(), REASON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(target.userId()))
				.andExpect(jsonPath("$.status").value("SUSPENDED"))
				.andExpect(jsonPath("$.suspendedUntil").value(until.toString()));

		assertThat(userStatus(target.userId())).isEqualTo("SUSPENDED");
		assertThat(suspendedUntil(target.userId())).isEqualTo(until);
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("user_id", target.userId()).containsEntry("change_type", "STATUS")
				.containsEntry("before_value", "ACTIVE").containsEntry("after_value", "SUSPENDED")
				.containsEntry("reason", REASON).containsEntry("changed_by", admin.userId()));
		Map<String, Object> audit = singleAudit();
		assertThat(audit).containsEntry("action", "USER_SUSPEND").containsEntry("actor_id", admin.userId())
				.containsEntry("target_type", "USER").containsEntry("target_id", target.userId())
				.containsEntry("result", "SUCCESS");
		JsonNode detail = detail(audit);
		assertThat(detail.get("suspendedUntil").asString()).isEqualTo(until.toString());
		assertThat(detail.get("reason").asString()).isEqualTo(REASON);
		assertThat(detail.get("source").asString()).isEqualTo("ADMIN");

		assertThat(client.refreshKeys(target.userId())).isEmpty();
		assertThat(client.refreshKeys(admin.userId())).hasSize(1);
		assertThat(client.refreshKeys(other.userId())).hasSize(1);
		// 계정 알림 유형이 없어 정지는 알림을 만들지 않는다(contracts §3.4).
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
	}

	@Test
	@DisplayName("정지된 회원은 기존 Refresh Token으로 재발급할 수 없고 로그인도 403 USER_SUSPENDED다")
	void suspendedUserIsLockedOut() throws Exception {
		changeStatus(target.userId(), body("SUSPENDED", null, REASON)).andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(AdminTestClient.REFRESH_COOKIE, target.refreshToken())))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(client.json(post("/api/v1/auth/email/login"),
						Map.of("email", "target@example.com", "password", PASSWORD)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
	}

	@Test
	@DisplayName("suspendedUntil을 생략하거나 null로 보내면 무기한 정지다")
	void suspendIndefinitely() throws Exception {
		Map<String, Object> omitted = new LinkedHashMap<>();
		omitted.put("status", "SUSPENDED");
		omitted.put("reason", REASON);

		changeStatus(target.userId(), omitted)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUSPENDED"))
				.andExpect(jsonPath("$.suspendedUntil").value(nullValue()));
		assertThat(userStatus(target.userId())).isEqualTo("SUSPENDED");
		assertThat(suspendedUntil(target.userId())).isNull();

		changeStatus(other.userId(), body("SUSPENDED", null, REASON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspendedUntil").value(nullValue()));
		assertThat(suspendedUntil(other.userId())).isNull();
		assertThat(client.refreshKeys(other.userId())).isEmpty();
	}

	@Test
	@DisplayName("이미 정지 중이면 기간만 바꾼다. SUSPENDED→SUSPENDED 이력이 남고 토큰을 다시 폐기한다")
	void changeSuspensionPeriod() throws Exception {
		Instant previous = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
		suspendDirectly(target.userId(), previous);
		Instant until = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);

		changeStatus(target.userId(), body("SUSPENDED", until.toString(), REASON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspendedUntil").value(until.toString()));

		assertThat(suspendedUntil(target.userId())).isEqualTo(until);
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "SUSPENDED"));
		assertThat(detail(singleAudit()).get("previousSuspendedUntil").asString()).isEqualTo(previous.toString());
		assertThat(client.refreshKeys(target.userId())).isEmpty();
	}

	@Test
	@DisplayName("다른 관리자를 정지하면 그 관리자의 남은 토큰은 관리자 API에서 바로 403이다")
	void suspendedAdminLosesAdminAccess() throws Exception {
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", target.userId());
		Member targetAdmin = client.refresh(target);

		changeStatus(target.userId(), body("SUSPENDED", null, REASON)).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(targetAdmin)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	// --- 해제 ---

	@Test
	@DisplayName("정지 해제: ACTIVE가 되고 종료 시각이 지워지며 이력·USER_ACTIVATE 감사가 남는다. 토큰은 폐기하지 않는다")
	void activate() throws Exception {
		Instant previous = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);
		suspendDirectly(target.userId(), previous);

		changeStatus(target.userId(), body("ACTIVE", null, "소명 확인"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(target.userId()))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.suspendedUntil").value(nullValue()));

		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
		assertThat(suspendedUntil(target.userId())).isNull();
		assertThat(histories()).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "ACTIVE")
				.containsEntry("reason", "소명 확인").containsEntry("changed_by", admin.userId()));
		Map<String, Object> audit = singleAudit();
		assertThat(audit).containsEntry("action", "USER_ACTIVATE").containsEntry("actor_id", admin.userId());
		assertThat(detail(audit).get("previousSuspendedUntil").asString()).isEqualTo(previous.toString());
		assertThat(client.refreshKeys(target.userId())).hasSize(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
	}

	@Test
	@DisplayName("기간이 지났지만 상태값이 아직 SUSPENDED인 회원도 해제할 수 있다")
	void activateExpiredSuspension() throws Exception {
		suspendDirectly(target.userId(), Instant.now().minus(Duration.ofMinutes(1)));

		changeStatus(target.userId(), body("ACTIVE", null, REASON)).andExpect(status().isOk());

		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("사유는 앞뒤 공백을 떼고 저장한다")
	void reasonIsTrimmed() throws Exception {
		changeStatus(target.userId(), body("SUSPENDED", null, "  " + REASON + "  ")).andExpect(status().isOk());

		assertThat(histories().get(0)).containsEntry("reason", REASON);
		assertThat(detail(singleAudit()).get("reason").asString()).isEqualTo(REASON);
	}

	// --- 오류 ---

	@Test
	@DisplayName("ACTIVE 회원을 ACTIVE로 바꾸면 409 CONFLICT다")
	void activeToActiveIsConflict() throws Exception {
		changeStatus(target.userId(), body("ACTIVE", null, REASON))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		assertUnchanged();
	}

	@Test
	@DisplayName("본인 대상은 403 FORBIDDEN이고 아무것도 바뀌지 않는다(명세 오류 표)")
	void selfIsForbidden() throws Exception {
		changeStatus(admin.userId(), body("SUSPENDED", null, REASON))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("본인의 상태는 변경할 수 없습니다."));

		assertThat(userStatus(admin.userId())).isEqualTo("ACTIVE");
		assertThat(client.refreshKeys(admin.userId())).hasSize(1);
		assertUnchanged();
	}

	@Test
	@DisplayName("없는 회원은 404 NOT_FOUND다")
	void missingTargetIsNotFound() throws Exception {
		changeStatus(999L, body("SUSPENDED", null, REASON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		assertUnchanged();
	}

	@Test
	@DisplayName("탈퇴한 회원은 409 CONFLICT다")
	void withdrawnTargetIsConflict() throws Exception {
		client.withdraw(target.userId());

		changeStatus(target.userId(), body("SUSPENDED", null, REASON))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		assertThat(userStatus(target.userId())).isEqualTo("WITHDRAWN");
		assertThat(histories()).isEmpty();
		assertThat(auditCount()).isZero();
	}

	static Stream<Arguments> invalidBodies() {
		Map<String, Object> missingReason = new HashMap<>();
		missingReason.put("status", "SUSPENDED");
		Map<String, Object> missingStatus = new HashMap<>();
		missingStatus.put("reason", REASON);
		String past = Instant.now().minus(Duration.ofMinutes(1)).toString();
		String future = Instant.now().plus(Duration.ofDays(1)).toString();
		return Stream.of(
				Arguments.of("사유 누락(명세 오류 표)", missingReason),
				Arguments.of("사유 null", body("SUSPENDED", null, null)),
				Arguments.of("사유 공백뿐", body("SUSPENDED", null, "   ")),
				Arguments.of("사유 501자", body("SUSPENDED", null, "가".repeat(501))),
				Arguments.of("status 누락", missingStatus),
				Arguments.of("status WITHDRAWN", body("WITHDRAWN", null, REASON)),
				Arguments.of("status 알 수 없는 값", body("BANNED", null, REASON)),
				Arguments.of("종료 시각이 과거", body("SUSPENDED", past, REASON)),
				Arguments.of("종료 시각 형식 오류", body("SUSPENDED", "next-week", REASON)),
				Arguments.of("ACTIVE와 종료 시각을 함께 보냄", body("ACTIVE", future, REASON)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	@DisplayName("요청 검증 실패는 400 INVALID_INPUT이고 상태·이력·감사·토큰이 그대로다")
	void invalidBodyIsRejected(String name, Map<String, Object> body) throws Exception {
		changeStatus(target.userId(), body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		assertUnchanged();
	}

	@Test
	@DisplayName("숫자가 아닌 경로 변수는 400이다")
	void nonNumericPathIsRejected() throws Exception {
		mockMvc.perform(client.json(patch("/api/v1/admin/users/abc/status"), body("SUSPENDED", null, REASON))
						.header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("userId: 값의 형식이 올바르지 않습니다."));
	}

	// --- 트랜잭션 ---

	@Test
	@DisplayName("감사 기록이 실패하면 정지가 롤백되고 Refresh Token도 폐기되지 않는다")
	void auditFailureRollsBack() throws Exception {
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			changeStatus(target.userId(), body("SUSPENDED", null, REASON))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}

		assertUnchanged();
	}

	// --- helpers ---

	private ResultActions changeStatus(long userId, Map<String, Object> body) throws Exception {
		return mockMvc.perform(client.json(patch("/api/v1/admin/users/" + userId + "/status"), body)
				.header("Authorization", bearer(admin)));
	}

	/** null 값도 키로 넣는다(명시적 null) */
	private static Map<String, Object> body(String status, String suspendedUntil, String reason) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status);
		body.put("suspendedUntil", suspendedUntil);
		body.put("reason", reason);
		return body;
	}

	private void suspendDirectly(long userId, Instant until) {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = ? WHERE user_id = ?",
				Timestamp.from(until), userId);
	}

	private String userStatus(long userId) {
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

	/** 대상이 ACTIVE 그대로이고 이력·감사가 없으며 토큰이 남아 있다 */
	private void assertUnchanged() {
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
		assertThat(suspendedUntil(target.userId())).isNull();
		assertThat(histories()).isEmpty();
		assertThat(auditCount()).isZero();
		assertThat(client.refreshKeys(target.userId())).hasSize(1);
	}

}
