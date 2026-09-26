package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.dto.request.EmailLoginRequest;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.service.EmailAuthService;
import com.reused.common.error.ErrorCode;
import com.reused.support.ConcurrentAttempts;
import com.reused.user.entity.AuthProvider;

/**
 * 이메일 계정 인증 통합 테스트. 실제 Postgres·Redis 위에서 돌고, 메일 발송과 카카오 호출만 대역으로 바꾼다.
 * 메일 대역이 받은 코드를 그대로 다음 요청에 넣어 화면 흐름과 같은 경로를 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EmailAuthIntegrationTest {

	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "hunter22!pw";
	private static final String NICKNAME = "재현";
	private static final String REFRESH_COOKIE = "refresh_token";

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
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute(
				"TRUNCATE notification_settings, user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	// --- 가입 ---

	@Test
	@DisplayName("가입하면 201과 토큰·쿠키를 받고 LOCAL 인증 수단이 argon2id 해시로 저장되며 소유 확인 메일이 1회 나간다")
	void signupCreatesLocalIdentityAndSendsVerification() throws Exception {
		// 대소문자는 정규화한다. 앞뒤 공백은 @Email 형식 검증에서 400으로 거절되므로 여기서 다루지 않는다.
		MvcResult result = mockMvc.perform(signup("User@Example.COM", PASSWORD, NICKNAME))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.nickname").value(NICKNAME))
				.andReturn();

		Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isTrue();

		Map<String, Object> identity = jdbcTemplate.queryForMap(
				"SELECT provider, provider_user_id, email, password_hash, email_verified_at FROM user_identities");
		assertThat(identity.get("provider")).isEqualTo("LOCAL");
		assertThat((String) identity.get("provider_user_id")).hasSize(36); // UUID
		assertThat(identity.get("email")).isEqualTo(EMAIL); // 소문자로 저장
		assertThat((String) identity.get("password_hash")).startsWith("$argon2id$").doesNotContain(PASSWORD);
		assertThat(identity.get("email_verified_at")).isNull();

		Long settings = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_settings", Long.class);
		assertThat(settings).isEqualTo(1);

		verify(mailSender, times(1)).sendVerificationCode(eq(EMAIL), any());
	}

	@Test
	@DisplayName("같은 이메일로 다시 가입하면 409다")
	void duplicateEmailIsConflict() throws Exception {
		signupUser();

		mockMvc.perform(signup(EMAIL, PASSWORD, "다른닉네임"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	@DisplayName("닉네임이 중복이면 409다")
	void duplicateNicknameIsConflict() throws Exception {
		signupUser();

		mockMvc.perform(signup("other@example.com", PASSWORD, NICKNAME))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	@DisplayName("비밀번호가 8자 미만이면 400 INVALID_INPUT이다")
	void shortPasswordIsRejected() throws Exception {
		mockMvc.perform(signup(EMAIL, "short7!", NICKNAME))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("약관 2건 중 하나라도 동의하지 않으면 400이다")
	void bothTermsMustBeAgreed() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", EMAIL);
		body.put("password", PASSWORD);
		body.put("nickname", NICKNAME);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", false);

		mockMvc.perform(json(post("/api/v1/auth/email/signup"), body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	// --- 로그인 ---

	@Test
	@DisplayName("이메일·비밀번호가 맞으면 200과 토큰·쿠키를 받는다")
	void loginSucceeds() throws Exception {
		signupUser();

		MvcResult result = mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.nickname").value(NICKNAME))
				.andReturn();

		assertThat(result.getResponse().getCookie(REFRESH_COOKIE)).isNotNull();
	}

	@Test
	@DisplayName("이메일 미존재와 비밀번호 불일치는 상태·코드·메시지가 완전히 같은 401이다")
	void loginFailuresAreIndistinguishable() throws Exception {
		signupUser();

		String wrongPassword = mockMvc.perform(login(EMAIL, "wrong-password!"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andReturn().getResponse().getContentAsString();
		String unknownEmail = mockMvc.perform(login("nobody@example.com", PASSWORD))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		assertThat(unknownEmail).isEqualTo(wrongPassword);
	}

	@Test
	@DisplayName("같은 계정으로 5회 실패하면 이후 시도는 올바른 비밀번호여도 429다")
	void repeatedLoginFailuresAreRateLimited() throws Exception {
		signupUser();

		for (int i = 0; i < 5; i++) {
			mockMvc.perform(login(EMAIL, "wrong-password!")).andExpect(status().isUnauthorized());
		}

		mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	@DisplayName("틀린 비밀번호 10건이 동시에 들어와도 검증까지 가는 것은 한도 5건뿐이고 나머지는 429다(시도를 검증 전에 센다)")
	void concurrentLoginGuessesCannotExceedLimit() throws Exception {
		signupUser();

		List<ErrorCode> outcomes = ConcurrentAttempts.run(10,
				() -> emailAuthService.login(new EmailLoginRequest(EMAIL, "wrong-password!")));

		assertThat(outcomes).filteredOn(code -> code == ErrorCode.UNAUTHENTICATED).hasSize(5);
		assertThat(outcomes).filteredOn(code -> code == ErrorCode.RATE_LIMITED).hasSize(5);
		mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	@DisplayName("한도 안에서 성공하면 시도 횟수가 초기화되어 다시 5회를 쓸 수 있다")
	void successfulLoginResetsAttempts() throws Exception {
		signupUser();
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(login(EMAIL, "wrong-password!")).andExpect(status().isUnauthorized());
		}
		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk());

		for (int i = 0; i < 5; i++) {
			mockMvc.perform(login(EMAIL, "wrong-password!")).andExpect(status().isUnauthorized());
		}
		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("이용정지 계정은 403 USER_SUSPENDED다")
	void suspendedUserCannotLogin() throws Exception {
		signupUser();
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED'");

		mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
	}

	@Test
	@DisplayName("정지 종료 시각이 아직 오지 않았으면 403 USER_SUSPENDED다")
	void timedSuspensionBlocksLogin() throws Exception {
		signupUser();
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() + interval '1 day'");

		mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
	}

	@Test
	@DisplayName("정지 기간이 지났으면 자동 해제 작업 전이라 상태값이 SUSPENDED여도 로그인된다")
	void expiredSuspensionCanLogin() throws Exception {
		signupUser();
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() - interval '1 minute'");

		mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	@DisplayName("탈퇴 회원 표시용 예약 닉네임으로는 가입할 수 없다(400)")
	void reservedNicknameIsRejected() throws Exception {
		mockMvc.perform(signup(EMAIL, PASSWORD, "탈퇴회원#1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("사용할 수 없는 닉네임입니다."));
		mockMvc.perform(signup(EMAIL, PASSWORD, "탈퇴한 사용자"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("사용할 수 없는 닉네임입니다."));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
		verify(mailSender, never()).sendVerificationCode(any(), any());
	}

	// --- 이메일 소유 확인 ---

	@Test
	@DisplayName("메일로 받은 코드로 확인하면 204이고 email_verified_at이 기록되며 코드는 재사용할 수 없다")
	void verificationConfirmMarksEmailVerified() throws Exception {
		Tokens tokens = signupUser();
		String code = lastVerificationCode();

		mockMvc.perform(confirmVerification(tokens, code)).andExpect(status().isNoContent());

		Object verifiedAt = jdbcTemplate.queryForObject("SELECT email_verified_at FROM user_identities", Object.class);
		assertThat(verifiedAt).isNotNull();

		mockMvc.perform(confirmVerification(tokens, code))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("틀린 코드는 400 INVALID_INPUT이다")
	void wrongVerificationCodeIsRejected() throws Exception {
		Tokens tokens = signupUser();

		mockMvc.perform(confirmVerification(tokens, "000000"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertThat(jdbcTemplate.queryForObject("SELECT email_verified_at FROM user_identities", Object.class)).isNull();
	}

	@Test
	@DisplayName("코드당 5회를 넘기면 429이고 그 코드는 폐기되어 정답도 통하지 않는다")
	void verificationAttemptsAreLimitedPerCode() throws Exception {
		Tokens tokens = signupUser();
		String code = lastVerificationCode();
		String wrong = code.equals("000000") ? "000001" : "000000";

		for (int i = 0; i < 5; i++) {
			mockMvc.perform(confirmVerification(tokens, wrong)).andExpect(status().isBadRequest());
		}
		mockMvc.perform(confirmVerification(tokens, wrong))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));

		mockMvc.perform(confirmVerification(tokens, code)).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("가입 직후 60초 안에 재발송을 요청하면 429다")
	void resendWithinGapIsRateLimited() throws Exception {
		Tokens tokens = signupUser();

		mockMvc.perform(resendVerification(tokens))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	@DisplayName("간격이 지나면 재발송이 204이고 메일이 다시 나간다")
	void resendAfterGapSendsAgain() throws Exception {
		Tokens tokens = signupUser();
		clearResendGap();

		mockMvc.perform(resendVerification(tokens)).andExpect(status().isNoContent());

		verify(mailSender, times(2)).sendVerificationCode(eq(EMAIL), any());
	}

	@Test
	@DisplayName("재발송 메일은 트랜잭션 밖에서 보낸다. 동기 SMTP를 기다리는 동안 DB 커넥션을 쥐지 않는다")
	void resendSendsMailOutsideTransaction() throws Exception {
		Tokens tokens = signupUser();
		clearResendGap();
		List<Boolean> transactionActiveAtSend = new CopyOnWriteArrayList<>();
		willAnswer(invocation -> {
			transactionActiveAtSend.add(TransactionSynchronizationManager.isActualTransactionActive());
			return null;
		}).given(mailSender).sendVerificationCode(eq(EMAIL), any());

		mockMvc.perform(resendVerification(tokens)).andExpect(status().isNoContent());

		assertThat(transactionActiveAtSend).containsExactly(false);
	}

	@Test
	@DisplayName("이미 소유 확인이 끝난 계정의 재발송은 409다")
	void resendForVerifiedAccountIsConflict() throws Exception {
		Tokens tokens = signupUser();
		mockMvc.perform(confirmVerification(tokens, lastVerificationCode())).andExpect(status().isNoContent());
		clearResendGap();

		mockMvc.perform(resendVerification(tokens))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	@DisplayName("이메일 없이 온보딩한 소셜 계정은 확인할 이메일이 없어 재발송이 409다")
	void resendForSocialAccountWithoutEmailIsConflict() throws Exception {
		String kakaoAccessToken = signupKakaoUser();

		mockMvc.perform(post("/api/v1/auth/email/verification")
						.header("Authorization", "Bearer " + kakaoAccessToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("등록된 이메일이 없습니다."));
		verify(mailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("소유 확인 엔드포인트는 토큰 없이 호출하면 401이다")
	void verificationRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/auth/email/verification"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- 비밀번호 재설정 ---

	@Test
	@DisplayName("재설정 코드로 비밀번호를 바꾸면 기존 Refresh Token이 전부 폐기되고 새 비밀번호로만 로그인된다")
	void passwordResetRotatesCredentialAndRevokesSessions() throws Exception {
		Tokens tokens = signupUser();
		clearResendGap();

		mockMvc.perform(json(post("/api/v1/auth/password/reset"), Map.of("email", EMAIL)))
				.andExpect(status().isNoContent());

		ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
		verify(mailSender).sendPasswordResetCode(eq(EMAIL), codeCaptor.capture());
		String newPassword = "new-password-99";

		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", EMAIL, "code", codeCaptor.getValue(), "newPassword", newPassword)))
				.andExpect(status().isNoContent());

		// 기존 세션은 끊긴다(NFR-AUTH-016)
		mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isUnauthorized());
		mockMvc.perform(login(EMAIL, newPassword)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("가입되지 않은 이메일의 재설정 요청도 204이며 메일은 나가지 않는다")
	void passwordResetForUnknownEmailIsSilent() throws Exception {
		mockMvc.perform(json(post("/api/v1/auth/password/reset"), Map.of("email", "nobody@example.com")))
				.andExpect(status().isNoContent());

		verify(mailSender, never()).sendPasswordResetCode(any(), any());
	}

	@Test
	@DisplayName("틀린 재설정 코드는 400이고 비밀번호는 바뀌지 않는다")
	void wrongResetCodeIsRejected() throws Exception {
		signupUser();
		clearResendGap();
		mockMvc.perform(json(post("/api/v1/auth/password/reset"), Map.of("email", EMAIL)))
				.andExpect(status().isNoContent());

		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", EMAIL, "code", "000000", "newPassword", "new-password-99")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		mockMvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("가입되지 않은 이메일로 재설정을 확정하려 하면 코드 불일치와 같은 400이다")
	void resetConfirmForUnknownEmailLooksLikeWrongCode() throws Exception {
		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", "nobody@example.com", "code", "000000", "newPassword", "new-password-99")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	// --- helpers ---

	private MockHttpServletRequestBuilder signup(String email, String password, String nickname) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", password);
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		return json(post("/api/v1/auth/email/signup"), body);
	}

	private MockHttpServletRequestBuilder login(String email, String password) {
		return json(post("/api/v1/auth/email/login"), Map.of("email", email, "password", password));
	}

	private MockHttpServletRequestBuilder resendVerification(Tokens tokens) {
		return post("/api/v1/auth/email/verification")
				.header("Authorization", "Bearer " + tokens.accessToken());
	}

	private MockHttpServletRequestBuilder confirmVerification(Tokens tokens, String code) {
		return json(post("/api/v1/auth/email/verification/confirm"), Map.of("code", code))
				.header("Authorization", "Bearer " + tokens.accessToken());
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private Tokens signupUser() throws Exception {
		MvcResult result = mockMvc.perform(signup(EMAIL, PASSWORD, NICKNAME))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(body.get("accessToken").asString(), result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	private String signupKakaoUser() throws Exception {
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willReturn("1234567890");
		MvcResult loginResult = mockMvc.perform(json(post("/api/v1/auth/oauth/kakao"),
						Map.of("code", "auth-code", "redirectUri", "https://reused.app/oauth/callback")))
				.andExpect(status().isOk()).andReturn();
		String signupToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
				.get("signupToken").asString();
		MvcResult signupResult = mockMvc.perform(json(post("/api/v1/auth/signup"),
						Map.of("signupToken", signupToken, "nickname", "카카오유저",
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(signupResult.getResponse().getContentAsString()).get("accessToken").asString();
	}

	private String lastVerificationCode() {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(mailSender, atLeastOnce()).sendVerificationCode(eq(EMAIL), captor.capture());
		return captor.getValue();
	}

	/**
	 * 재발송 간격 잠금(60초)을 테스트에서 기다릴 수 없으므로 키를 직접 지운다.
	 * 소셜 인증 수단도 이메일을 가질 수 있으므로(ADR-019) 제공자를 함께 조건에 넣는다.
	 */
	private void clearResendGap() {
		Long identityId = jdbcTemplate.queryForObject(
				"SELECT identity_id FROM user_identities WHERE provider = 'LOCAL' AND email = ?", Long.class, EMAIL);
		redisTemplate.delete("reused:auth:resend-gap:" + identityId);
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
