package com.reused.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.reused.user.entity.AuthProvider;

/**
 * 인증 흐름이 남기는 감사 로그(FR-LOG-001). 요청이 끝난 뒤 audit_logs를 직접 조회한다.
 * detail에 이메일 같은 개인정보가 들어가지 않는지도 함께 본다(NFR-LOG-003).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthAuditIntegrationTest {

	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "hunter22!pw";
	private static final String NICKNAME = "재현";
	private static final String REFRESH_COOKIE = "refresh_token";
	private static final String REJECT_SETTINGS_CONSTRAINT = "ck_notification_settings_test_reject";

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

	// --- 가입 ---

	@Test
	@DisplayName("이메일 가입은 AUTH_SIGNUP 성공을 새 회원 기준으로 남기고 요청 IP를 함께 저장한다")
	void emailSignupIsAudited() throws Exception {
		signupLocal(EMAIL, NICKNAME);

		Map<String, Object> row = single("AUTH_SIGNUP");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("target_type")).isEqualTo("USER");
		assertThat(row.get("target_id")).isEqualTo(1L);
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("provider")).isEqualTo("LOCAL");
		assertThat(row.get("ip")).isEqualTo("127.0.0.1");
		assertThat((String) row.get("detail")).doesNotContain(EMAIL, NICKNAME);
	}

	@Test
	@DisplayName("소셜 가입은 provider=KAKAO로 남는다. 가입 필요 응답(SIGNUP_REQUIRED)은 로그인 기록이 아니다")
	void kakaoSignupIsAudited() throws Exception {
		signupKakao(NICKNAME);

		Map<String, Object> row = single("AUTH_SIGNUP");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("provider")).isEqualTo("KAKAO");
		assertThat(rows("AUTH_LOGIN")).isEmpty();
	}

	@Test
	@DisplayName("커밋에 실패한 가입은 이메일·소셜 모두 기록하지 않고 소유 확인 메일도 보내지 않는다")
	void rolledBackSignupIsNotAudited() throws Exception {
		// 알림 설정 INSERT는 커밋할 때 나간다. 그 INSERT를 거부해 가입 본문(토큰 발급까지)이 다 실행된 뒤 롤백되게 한다.
		jdbcTemplate.execute("ALTER TABLE notification_settings ADD CONSTRAINT " + REJECT_SETTINGS_CONSTRAINT
				+ " CHECK (false) NOT VALID");
		try {
			mockMvc.perform(signupRequest(EMAIL, NICKNAME)).andExpect(status().isInternalServerError());
			mockMvc.perform(kakaoSignupRequest(NICKNAME)).andExpect(status().isInternalServerError());
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE notification_settings DROP CONSTRAINT " + REJECT_SETTINGS_CONSTRAINT);
		}

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
		assertThat(rows("AUTH_SIGNUP")).isEmpty();
		verify(mailSender, never()).sendVerificationCode(any(), any());
	}

	// --- 로그인 ---

	@Test
	@DisplayName("이메일 로그인 성공은 AUTH_LOGIN SUCCESS다")
	void emailLoginSuccessIsAudited() throws Exception {
		signupLocal(EMAIL, NICKNAME);

		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk());

		Map<String, Object> row = single("AUTH_LOGIN");
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("target_id")).isEqualTo(1L);
		assertThat(row.get("provider")).isEqualTo("LOCAL");
	}

	@Test
	@DisplayName("카카오 로그인 성공은 provider=KAKAO인 AUTH_LOGIN SUCCESS다")
	void kakaoLoginSuccessIsAudited() throws Exception {
		signupKakao(NICKNAME);

		mockMvc.perform(oauthLogin()).andExpect(status().isOk());

		Map<String, Object> row = single("AUTH_LOGIN");
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("provider")).isEqualTo("KAKAO");
	}

	@Test
	@DisplayName("없는 이메일로 로그인하면 행위자·대상 없이 UNKNOWN_ACCOUNT로 남고 이메일은 기록하지 않는다")
	void unknownEmailLoginIsAuditedWithoutActor() throws Exception {
		mockMvc.perform(login("nobody@example.com", PASSWORD)).andExpect(status().isUnauthorized());

		Map<String, Object> row = single("AUTH_LOGIN");
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("actor_id")).isNull();
		assertThat(row.get("target_type")).isNull();
		assertThat(row.get("target_id")).isNull();
		assertThat(row.get("provider")).isEqualTo("LOCAL");
		assertThat(row.get("reason")).isEqualTo("UNKNOWN_ACCOUNT");
		assertThat((String) row.get("detail")).doesNotContain("nobody");
	}

	@Test
	@DisplayName("비밀번호가 틀리면 계정 주인을 행위자로 BAD_CREDENTIALS가 남는다")
	void wrongPasswordIsAuditedWithActor() throws Exception {
		signupLocal(EMAIL, NICKNAME);

		mockMvc.perform(login(EMAIL, "wrong-password!")).andExpect(status().isUnauthorized());

		Map<String, Object> row = single("AUTH_LOGIN");
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("target_id")).isEqualTo(1L);
		assertThat(row.get("reason")).isEqualTo("BAD_CREDENTIALS");
		assertThat((String) row.get("detail")).doesNotContain("wrong-password");
	}

	@Test
	@DisplayName("정지 계정의 로그인은 이메일·카카오 모두 SUSPENDED 실패로 남는다")
	void suspendedLoginIsAudited() throws Exception {
		signupLocal(EMAIL, NICKNAME);
		signupKakao("카카오유저");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED'");

		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isForbidden());
		mockMvc.perform(oauthLogin()).andExpect(status().isForbidden());

		List<Map<String, Object>> rows = rows("AUTH_LOGIN");
		assertThat(rows).extracting(row -> row.get("result")).containsOnly("FAILURE");
		assertThat(rows).extracting(row -> row.get("reason")).containsOnly("SUSPENDED");
		assertThat(rows).extracting(row -> row.get("provider")).containsExactly("LOCAL", "KAKAO");
		assertThat(rows).extracting(row -> row.get("actor_id")).containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("시도 한도를 넘긴 로그인은 RATE_LIMITED 실패로 남는다")
	void rateLimitedLoginIsAudited() throws Exception {
		signupLocal(EMAIL, NICKNAME);
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(login(EMAIL, "wrong-password!")).andExpect(status().isUnauthorized());
		}

		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isTooManyRequests());

		List<Map<String, Object>> rows = rows("AUTH_LOGIN");
		assertThat(rows).hasSize(6);
		assertThat(rows.get(5).get("reason")).isEqualTo("RATE_LIMITED");
		assertThat(rows.get(5).get("actor_id")).isEqualTo(1L);
	}

	// --- 로그아웃·재설정·재사용 ---

	@Test
	@DisplayName("로그아웃은 토큰의 사용자를 행위자로 AUTH_LOGOUT을 남긴다")
	void logoutIsAudited() throws Exception {
		Tokens tokens = signupLocal(EMAIL, NICKNAME);

		mockMvc.perform(post("/api/v1/auth/logout")
						.header("Authorization", "Bearer " + tokens.accessToken())
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isNoContent());

		Map<String, Object> row = single("AUTH_LOGOUT");
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("target_id")).isEqualTo(1L);
		assertThat(row.get("detail")).isNull();
	}

	@Test
	@DisplayName("비밀번호 재설정 성공은 AUTH_PASSWORD_RESET으로 남고 코드·비밀번호는 기록하지 않는다")
	void passwordResetIsAudited() throws Exception {
		signupLocal(EMAIL, NICKNAME);
		Long identityId = jdbcTemplate.queryForObject("SELECT identity_id FROM user_identities", Long.class);
		redisTemplate.delete("reused:auth:resend-gap:" + identityId);
		mockMvc.perform(json(post("/api/v1/auth/password/reset"), Map.of("email", EMAIL)))
				.andExpect(status().isNoContent());
		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(mailSender).sendPasswordResetCode(eq(EMAIL), code.capture());

		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", EMAIL, "code", code.getValue(), "newPassword", "new-password-99")))
				.andExpect(status().isNoContent());

		Map<String, Object> row = single("AUTH_PASSWORD_RESET");
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("actor_id")).isNull();
		assertThat(row.get("target_type")).isEqualTo("USER");
		assertThat(row.get("target_id")).isEqualTo(1L);
		assertThat(row.get("detail")).isNull();
	}

	@Test
	@DisplayName("틀린 재설정 코드는 재설정 성공으로 남지 않는다")
	void failedResetIsNotAudited() throws Exception {
		signupLocal(EMAIL, NICKNAME);

		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", EMAIL, "code", "000000", "newPassword", "new-password-99")))
				.andExpect(status().isBadRequest());

		assertThat(rows("AUTH_PASSWORD_RESET")).isEmpty();
	}

	@Test
	@DisplayName("폐기된 Refresh Token 재사용은 행위자 없이 계정을 대상으로 AUTH_TOKEN_REUSE_DETECTED 실패를 남긴다")
	void tokenReuseIsAudited() throws Exception {
		Tokens tokens = signupLocal(EMAIL, NICKNAME);
		mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isOk());
		assertThat(rows("AUTH_TOKEN_REUSE_DETECTED")).isEmpty(); // 정상 회전은 기록하지 않는다

		mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isUnauthorized());

		Map<String, Object> row = single("AUTH_TOKEN_REUSE_DETECTED");
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("actor_id")).isNull();
		assertThat(row.get("target_type")).isEqualTo("USER");
		assertThat(row.get("target_id")).isEqualTo(1L);
	}

	// --- 기록 실패 ---

	@Test
	@DisplayName("감사 로그를 쓸 수 없어도 가입·로그인·로그아웃은 정상 동작한다")
	void auditFailureNeverBreaksAuthFlow() throws Exception {
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			Tokens tokens = signupLocal(EMAIL, NICKNAME);
			mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk());
			mockMvc.perform(login("nobody@example.com", PASSWORD)).andExpect(status().isUnauthorized());
			mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + tokens.accessToken()))
					.andExpect(status().isNoContent());
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
	}

	// --- helpers ---

	private List<Map<String, Object>> rows(String action) {
		return jdbcTemplate.queryForList("""
				SELECT actor_id, target_type, target_id, result, host(ip_address) AS ip, detail::text AS detail,
				       detail->>'provider' AS provider, detail->>'reason' AS reason
				FROM audit_logs WHERE action = ? ORDER BY audit_log_id""", action);
	}

	private Map<String, Object> single(String action) {
		List<Map<String, Object>> rows = rows(action);
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private MockHttpServletRequestBuilder signupRequest(String email, String nickname) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		return json(post("/api/v1/auth/email/signup"), body);
	}

	private Tokens signupLocal(String email, String nickname) throws Exception {
		MvcResult result = mockMvc.perform(signupRequest(email, nickname))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(body.get("accessToken").asString(), result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	private void signupKakao(String nickname) throws Exception {
		mockMvc.perform(kakaoSignupRequest(nickname)).andExpect(status().isCreated());
	}

	/** 카카오 로그인으로 signupToken을 받아 온보딩 요청을 만든다. */
	private MockHttpServletRequestBuilder kakaoSignupRequest(String nickname) throws Exception {
		MvcResult login = mockMvc.perform(oauthLogin()).andExpect(status().isOk()).andReturn();
		String signupToken = objectMapper.readTree(login.getResponse().getContentAsString())
				.get("signupToken").asString();
		return json(post("/api/v1/auth/signup"),
				Map.of("signupToken", signupToken, "nickname", nickname,
						"termsOfServiceAgreed", true, "privacyPolicyAgreed", true));
	}

	private MockHttpServletRequestBuilder oauthLogin() {
		return json(post("/api/v1/auth/oauth/kakao"),
				Map.of("code", "auth-code", "redirectUri", "https://reused.app/oauth/callback"));
	}

	private MockHttpServletRequestBuilder login(String email, String password) {
		return json(post("/api/v1/auth/email/login"), Map.of("email", email, "password", password));
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
