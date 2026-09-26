package com.reused.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;
import com.reused.user.entity.UserRole;

/**
 * 관리자 API 다섯 개의 권한 규칙. URL 규칙(hasRole ADMIN)과 {@code AdminAccessInterceptor}의 DB 재확인이 모든 엔드포인트에
 * 똑같이 걸리는지, 막힌 쓰기 요청이 아무것도 바꾸지 않는지 본다.
 *
 * <p>관리자의 남은 ADMIN 토큰은 인터셉터가 DB로 다시 본다(contracts §1.5). 행이 없거나 탈퇴면 일반 엔드포인트와 같은
 * "탈퇴 토큰 401" 규칙으로 401 UNAUTHENTICATED, 정지·역할 회수면 403 FORBIDDEN이다. USER 토큰은 탈퇴 여부와 관계없이
 * URL 규칙이 먼저 403으로 막는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminEndpointAccessIntegrationTest {

	private static final String REASON = "운영 조치";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private AdminTestClient client;
	private Member admin;
	private Member user;

	@BeforeEach
	void resetState() throws Exception {
		client = new AdminTestClient(mockMvc, objectMapper, jdbcTemplate, redisTemplate);
		client.reset();
		admin = client.signupAdmin("admin@example.com", "관리자");
		user = client.signup("user@example.com", "일반회원");
		jdbcTemplate.update("DELETE FROM audit_logs");
	}

	/** 쓰기 요청은 통과했다면 실제로 대상을 바꾸는 유효한 본문을 싣는다 */
	static Stream<Endpoint> endpoints() {
		return Stream.of(
				new Endpoint("GET /admin/users", id -> get("/api/v1/admin/users"), null),
				new Endpoint("PATCH /admin/users/{id}/role", id -> patch("/api/v1/admin/users/" + id + "/role"),
						Map.of("role", "ADMIN", "reason", REASON)),
				new Endpoint("PATCH /admin/users/{id}/status", id -> patch("/api/v1/admin/users/" + id + "/status"),
						Map.of("status", "SUSPENDED", "reason", REASON)),
				new Endpoint("GET /admin/dashboard", id -> get("/api/v1/admin/dashboard"), null),
				new Endpoint("GET /admin/audit-logs", id -> get("/api/v1/admin/audit-logs"), null));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("토큰이 없으면 401 UNAUTHENTICATED다")
	void noTokenIsUnauthenticated(Endpoint endpoint) throws Exception {
		mockMvc.perform(endpoint.request(user.userId(), objectMapper))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertTargetUnchanged();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("서명이 틀린 토큰은 인증 필터가 바로 401로 거절한다")
	void invalidTokenIsUnauthenticated(Endpoint endpoint) throws Exception {
		mockMvc.perform(endpoint.request(user.userId(), objectMapper).header("Authorization", "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertTargetUnchanged();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("USER 토큰은 403 FORBIDDEN이고 쓰기 요청은 아무것도 바꾸지 않는다")
	void userTokenIsForbidden(Endpoint endpoint) throws Exception {
		long otherUser = client.insertUser("다른회원");

		perform(endpoint, otherUser, user)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertThat(role(otherUser)).isEqualTo("USER");
		assertNothingRecorded();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("탈퇴한 일반 회원의 토큰도 403이다(역할 규칙이 먼저 막는다)")
	void withdrawnUserIsForbidden(Endpoint endpoint) throws Exception {
		client.withdraw(user.userId());
		long otherUser = client.insertUser("다른회원");

		perform(endpoint, otherUser, user)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertNothingRecorded();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("탈퇴한 관리자의 남은 ADMIN 토큰은 401 UNAUTHENTICATED다(관리자 DB 재확인, 탈퇴 토큰 규칙)")
	void withdrawnAdminIsUnauthenticated(Endpoint endpoint) throws Exception {
		client.withdraw(admin.userId());

		perform(endpoint, user.userId(), admin)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertTargetUnchanged();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("무기한 정지된 관리자는 403 FORBIDDEN이다")
	void indefinitelySuspendedAdminIsForbidden(Endpoint endpoint) throws Exception {
		client.suspend(admin.userId(), "NULL");

		assertAdminBlocked(endpoint);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("기간이 남은 정지 관리자는 403 FORBIDDEN이다")
	void suspendedAdminIsForbidden(Endpoint endpoint) throws Exception {
		client.suspend(admin.userId(), "now() + interval '1 day'");

		assertAdminBlocked(endpoint);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("DB에서 역할이 회수된 관리자의 남은 ADMIN 토큰은 403 FORBIDDEN이다")
	void revokedAdminIsForbidden(Endpoint endpoint) throws Exception {
		jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE user_id = ?", admin.userId());

		assertAdminBlocked(endpoint);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("DB에 없는 사용자의 ADMIN 토큰은 401 UNAUTHENTICATED다")
	void unknownAdminIsUnauthenticated(Endpoint endpoint) throws Exception {
		String forged = tokenProvider.issueAccessToken(999L, UserRole.ADMIN);

		mockMvc.perform(endpoint.request(user.userId(), objectMapper).header("Authorization", "Bearer " + forged))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertTargetUnchanged();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("정지 기간이 이미 지난 관리자는 정지로 보지 않고 통과한다")
	void expiredSuspensionPasses(Endpoint endpoint) throws Exception {
		client.suspend(admin.userId(), "now() - interval '1 minute'");

		perform(endpoint, user.userId(), admin).andExpect(status().isOk());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endpoints")
	@DisplayName("활성 관리자는 200이다")
	void activeAdminPasses(Endpoint endpoint) throws Exception {
		perform(endpoint, user.userId(), admin).andExpect(status().isOk());
	}

	// --- helpers ---

	private ResultActions perform(Endpoint endpoint, long targetId, Member caller) throws Exception {
		return mockMvc.perform(endpoint.request(targetId, objectMapper)
				.header("Authorization", AdminTestClient.bearer(caller)));
	}

	private void assertAdminBlocked(Endpoint endpoint) throws Exception {
		perform(endpoint, user.userId(), admin)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertTargetUnchanged();
	}

	private String role(long userId) {
		return jdbcTemplate.queryForObject("SELECT role FROM users WHERE user_id = ?", String.class, userId);
	}

	/** 쓰기 요청이 막혔으면 대상의 역할·상태가 그대로이고 이력·감사가 없다 */
	private void assertTargetUnchanged() {
		assertThat(jdbcTemplate.queryForMap("SELECT role, status FROM users WHERE user_id = ?", user.userId()))
				.containsEntry("role", "USER")
				.containsEntry("status", "ACTIVE");
		assertNothingRecorded();
	}

	private void assertNothingRecorded() {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_status_histories", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
	}

	/**
	 * @param body null이면 본문 없음
	 */
	record Endpoint(String name, LongFunction<MockHttpServletRequestBuilder> factory, Map<String, ?> body) {

		MockHttpServletRequestBuilder request(long targetId, ObjectMapper objectMapper) {
			MockHttpServletRequestBuilder builder = factory.apply(targetId);
			if (body == null) {
				return builder;
			}
			return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}

		@Override
		public String toString() {
			return name;
		}

	}

}
