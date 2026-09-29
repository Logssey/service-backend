package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.service.EmailAuthService;
import com.reused.common.error.ErrorCode;
import com.reused.support.ConcurrentAttempts;
import com.reused.user.entity.AuthProvider;

/**
 * 비밀번호 변경({@code PATCH /users/me/password}). 모든 Refresh Token 폐기, 현재 비밀번호 확인과 시도 제한,
 * 감사 기록을 실제 Postgres·Redis로 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PasswordChangeIntegrationTest {

	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "hunter22!pw";
	private static final String NEW_PASSWORD = "hunter33!pw";
	private static final String REFRESH_COOKIE = "refresh_token";
	private static final String URL = "/api/v1/users/me/password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private EmailAuthService emailAuthService;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willReturn("1234567890");
	}

	// --- 정상 ---

	@Test
	@DisplayName("현재 비밀번호가 맞으면 204(본문 없음)이고 새 비밀번호로만 로그인되며 새 토큰은 주지 않는다")
	void changesPassword() throws Exception {
		Tokens tokens = signupLocalUser();
		String oldHash = passwordHash();

		MvcResult result = mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""))
				.andReturn();
		assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();

		assertThat(passwordHash()).startsWith("$argon2id$").isNotEqualTo(oldHash).doesNotContain(NEW_PASSWORD);
		mockMvc.perform(login(PASSWORD)).andExpect(status().isUnauthorized());
		mockMvc.perform(login(NEW_PASSWORD)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("성공하면 모든 기기의 Refresh Token이 폐기된다(NFR-AUTH-016)")
	void revokesAllRefreshTokens() throws Exception {
		Tokens first = signupLocalUser();
		String secondDevice = loginRefreshToken(PASSWORD);
		assertThat(refreshKeys(1)).hasSize(2);

		mockMvc.perform(changePassword(first.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isNoContent());

		assertThat(refreshKeys(1)).isEmpty();
		for (String refreshToken : List.of(first.refreshToken(), secondDevice)) {
			mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, refreshToken)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
	}

	@Test
	@DisplayName("성공은 AUTH_PASSWORD_CHANGE SUCCESS로 감사 기록되고 비밀번호는 detail에 남지 않는다")
	void recordsSuccessAudit() throws Exception {
		Tokens tokens = signupLocalUser();

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isNoContent());

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT actor_id, target_type, target_id, result, "
				+ "detail::text AS detail FROM audit_logs WHERE action = 'AUTH_PASSWORD_CHANGE'");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("target_type")).isEqualTo("USER");
		assertThat(row.get("target_id")).isEqualTo(1L);
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("detail")).isNull();
	}

	@Test
	@DisplayName("이용정지 회원도 비밀번호를 바꿀 수 있다(명세에 403 없음)")
	void suspendedUserCanChangePassword() throws Exception {
		Tokens tokens = signupLocalUser();
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() + interval '7 days'");

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("길이 경계값 8자와 128자는 허용된다")
	void acceptsBoundaryLengths() throws Exception {
		Tokens tokens = signupLocalUser();

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, "a".repeat(8)))
				.andExpect(status().isNoContent());
		mockMvc.perform(changePassword(tokens.accessToken(), "a".repeat(8), "b".repeat(128)))
				.andExpect(status().isNoContent());
		mockMvc.perform(login("b".repeat(128))).andExpect(status().isOk());
	}

	// --- 401 현재 비밀번호 불일치 ---

	@Test
	@DisplayName("현재 비밀번호가 틀리면 401이고 비밀번호·세션은 그대로이며 FAILURE로 감사 기록된다")
	void wrongCurrentPasswordIsUnauthenticated() throws Exception {
		Tokens tokens = signupLocalUser();
		String oldHash = passwordHash();

		mockMvc.perform(changePassword(tokens.accessToken(), "wrong-password", NEW_PASSWORD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));

		assertThat(passwordHash()).isEqualTo(oldHash);
		mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isOk());
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT actor_id, result, detail ->> 'reason' AS reason "
				+ "FROM audit_logs WHERE action = 'AUTH_PASSWORD_CHANGE'");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("reason")).isEqualTo("BAD_CREDENTIALS");
	}

	// --- 429 추측 제한(문서에 없는 추가) ---

	@Test
	@DisplayName("불일치가 5회 쌓이면 맞는 비밀번호여도 429이고 FAILURE(RATE_LIMITED)로 기록된다. 한도는 로그인과 함께 센다")
	void rateLimitsGuessing() throws Exception {
		Tokens tokens = signupLocalUser();
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(changePassword(tokens.accessToken(), "wrong-password", NEW_PASSWORD))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));

		mockMvc.perform(login(PASSWORD)).andExpect(status().isTooManyRequests());
		mockMvc.perform(login(NEW_PASSWORD)).andExpect(status().isTooManyRequests());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'AUTH_PASSWORD_CHANGE' "
				+ "AND result = 'FAILURE' AND detail ->> 'reason' = 'RATE_LIMITED'", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'AUTH_PASSWORD_CHANGE' "
				+ "AND result = 'SUCCESS'", Long.class)).isZero();
	}

	@Test
	@DisplayName("틀린 현재 비밀번호 10건이 동시에 들어와도 검증까지 가는 것은 5건뿐이고 나머지는 429다")
	void concurrentGuessesCannotExceedLimit() throws Exception {
		signupLocalUser();
		Long userId = jdbcTemplate.queryForObject("SELECT user_id FROM users", Long.class);
		String oldHash = passwordHash();

		List<ErrorCode> outcomes = ConcurrentAttempts.run(10,
				() -> emailAuthService.changePassword(userId, "wrong-password", NEW_PASSWORD));

		assertThat(outcomes).filteredOn(code -> code == ErrorCode.UNAUTHENTICATED).hasSize(5);
		assertThat(outcomes).filteredOn(code -> code == ErrorCode.RATE_LIMITED).hasSize(5);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'AUTH_PASSWORD_CHANGE' "
				+ "AND detail ->> 'reason' = 'BAD_CREDENTIALS'", Long.class)).isEqualTo(5L);
		assertThat(passwordHash()).isEqualTo(oldHash);
	}

	@Test
	@DisplayName("성공하면 쌓인 실패 횟수를 초기화한다")
	void successResetsFailureCounter() throws Exception {
		Tokens tokens = signupLocalUser();
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(changePassword(tokens.accessToken(), "wrong-password", NEW_PASSWORD))
					.andExpect(status().isUnauthorized());
		}
		String failKey = "reused:auth:login-fail:" + identityId();
		assertThat(redisTemplate.opsForValue().get(failKey)).isEqualTo("2");

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isNoContent());

		assertThat(redisTemplate.hasKey(failKey)).isFalse();
	}

	// --- 409 소셜 계정 ---

	@Test
	@DisplayName("소셜 계정은 409 CONFLICT다")
	void socialAccountHasNoPassword() throws Exception {
		String accessToken = signupKakaoUser();

		mockMvc.perform(changePassword(accessToken, PASSWORD, NEW_PASSWORD))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("소셜 계정은 변경할 비밀번호가 없습니다."));
	}

	@Test
	@DisplayName("입력 검증이 먼저라 소셜 계정이어도 새 비밀번호 길이 위반은 400이다")
	void validationComesBeforeSocialCheck() throws Exception {
		String accessToken = signupKakaoUser();

		mockMvc.perform(changePassword(accessToken, PASSWORD, "short"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	// --- 400 입력 ---

	@Test
	@DisplayName("새 비밀번호 7자·129자, 빈 현재 비밀번호, 빠진 필드, 깨진 JSON은 400이고 비밀번호는 그대로다")
	void rejectsInvalidInput() throws Exception {
		Tokens tokens = signupLocalUser();
		String oldHash = passwordHash();

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, "a".repeat(7)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(startsWith("newPassword:")));
		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, "a".repeat(129)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(changePassword(tokens.accessToken(), "", NEW_PASSWORD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("currentPassword:")));
		mockMvc.perform(json(patch(URL), Map.of("currentPassword", PASSWORD))
						.header("Authorization", "Bearer " + tokens.accessToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":")
						.header("Authorization", "Bearer " + tokens.accessToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		assertThat(passwordHash()).isEqualTo(oldHash);
	}

	// --- 401 인증 ---

	@Test
	@DisplayName("토큰이 없거나 잘못되면 401이다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(json(patch(URL), Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(changePassword("not-a-jwt", PASSWORD, NEW_PASSWORD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("탈퇴한 회원의 남은 Access Token은 401이다")
	void withdrawnUserIsUnauthenticated() throws Exception {
		Tokens tokens = signupLocalUser();
		String oldHash = passwordHash();
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now()");

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		assertThat(passwordHash()).isEqualTo(oldHash);
	}

	@Test
	@DisplayName("인증 수단 행이 없으면 401이다")
	void missingIdentityIsUnauthenticated() throws Exception {
		Tokens tokens = signupLocalUser();
		jdbcTemplate.update("DELETE FROM user_identities");

		mockMvc.perform(changePassword(tokens.accessToken(), PASSWORD, NEW_PASSWORD))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- helpers ---

	private MockHttpServletRequestBuilder changePassword(String accessToken, String current, String next) {
		return json(patch(URL), Map.of("currentPassword", current, "newPassword", next))
				.header("Authorization", "Bearer " + accessToken);
	}

	private MockHttpServletRequestBuilder login(String password) {
		return json(post("/api/v1/auth/email/login"), Map.of("email", EMAIL, "password", password));
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private Tokens signupLocalUser() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", EMAIL);
		body.put("password", PASSWORD);
		body.put("nickname", "재현");
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		MvcResult result = mockMvc.perform(json(post("/api/v1/auth/email/signup"), body))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(json.get("accessToken").asString(), result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	private String loginRefreshToken(String password) throws Exception {
		MvcResult result = mockMvc.perform(login(password)).andExpect(status().isOk()).andReturn();
		return result.getResponse().getCookie(REFRESH_COOKIE).getValue();
	}

	private String signupKakaoUser() throws Exception {
		MvcResult login = mockMvc.perform(json(post("/api/v1/auth/oauth/kakao"),
						Map.of("code", "auth-code", "redirectUri", "https://reused.app/oauth/callback")))
				.andExpect(status().isOk())
				.andReturn();
		String signupToken = objectMapper.readTree(login.getResponse().getContentAsString())
				.get("signupToken").asString();
		MvcResult signup = mockMvc.perform(json(post("/api/v1/auth/signup"),
						Map.of("signupToken", signupToken, "nickname", "카카오유저",
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(signup.getResponse().getContentAsString()).get("accessToken").asString();
	}

	private String passwordHash() {
		return jdbcTemplate.queryForObject("SELECT password_hash FROM user_identities", String.class);
	}

	private Long identityId() {
		return jdbcTemplate.queryForObject("SELECT identity_id FROM user_identities", Long.class);
	}

	private Set<String> refreshKeys(long userId) {
		return redisTemplate.keys("reused:auth:refresh:" + userId + ":*");
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
