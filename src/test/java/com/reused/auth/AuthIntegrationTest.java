package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.KakaoOAuthClient;

/**
 * 인증 흐름 통합 테스트. 실제 Postgres·Redis 컨테이너 위에서 돌고, 외부 카카오 호출만 대역으로 바꾼다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

	private static final String KAKAO_USER_ID = "1234567890";
	private static final String REFRESH_COOKIE = "refresh_token";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private KakaoOAuthClient kakaoOAuthClient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE notification_settings, user_status_histories, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willReturn(KAKAO_USER_ID);
	}

	@Test
	@DisplayName("가입하지 않은 카카오 계정은 SIGNUP_REQUIRED와 signupToken을 받는다")
	void newUserGetsSignupToken() throws Exception {
		mockMvc.perform(kakaoLogin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SIGNUP_REQUIRED"))
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.signupToken").isNotEmpty())
				.andExpect(jsonPath("$.user").doesNotExist());
	}

	@Test
	@DisplayName("가입하면 Access Token과 Refresh 쿠키를 받고 알림 설정이 함께 만들어진다")
	void signupCreatesUserAndNotificationSettings() throws Exception {
		String signupToken = signupTokenFromLogin();

		MvcResult result = mockMvc.perform(signup(signupToken, "재현"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.nickname").value("재현"))
				.andReturn();

		Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getValue()).isNotBlank();

		Long userCount = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class);
		Long settingsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_settings", Long.class);
		assertThat(userCount).isEqualTo(1);
		assertThat(settingsCount).isEqualTo(1);
	}

	@Test
	@DisplayName("이미 가입한 계정은 LOGIN 상태로 바로 토큰을 받는다")
	void existingUserLogsIn() throws Exception {
		signupNewUser("재현");

		mockMvc.perform(kakaoLogin())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOGIN"))
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.signupToken").doesNotExist())
				.andExpect(jsonPath("$.user.nickname").value("재현"));
	}

	@Test
	@DisplayName("닉네임이 중복이면 409로 거절한다")
	void duplicateNicknameIsRejected() throws Exception {
		signupNewUser("재현");
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willReturn("9999999999");
		String signupToken = signupTokenFromLogin();

		mockMvc.perform(signup(signupToken, "재현"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	@DisplayName("닉네임이 2자 미만이면 400 INVALID_INPUT이다")
	void tooShortNicknameIsRejected() throws Exception {
		String signupToken = signupTokenFromLogin();

		mockMvc.perform(signup(signupToken, "a"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("약관에 동의하지 않으면 400이다")
	void termsMustBeAgreed() throws Exception {
		String signupToken = signupTokenFromLogin();
		String body = objectMapper.writeValueAsString(
				new java.util.LinkedHashMap<>(java.util.Map.of(
						"signupToken", signupToken, "nickname", "재현", "termsAgreed", false)));

		mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("signupToken 자리에 Access Token을 넣으면 거절한다")
	void accessTokenCannotBeUsedAsSignupToken() throws Exception {
		Tokens tokens = signupNewUser("재현");

		mockMvc.perform(signup(tokens.accessToken(), "다른닉네임"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("이용정지 계정은 403 USER_SUSPENDED로 로그인이 막힌다")
	void suspendedUserCannotLogin() throws Exception {
		signupNewUser("재현");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED'");

		mockMvc.perform(kakaoLogin())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
	}

	@Test
	@DisplayName("재발급하면 새 Access Token과 새 Refresh 쿠키를 받는다")
	void refreshRotatesTokens() throws Exception {
		Tokens tokens = signupNewUser("재현");

		MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn();

		Cookie rotated = result.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(rotated).isNotNull();
		assertThat(rotated.getValue()).isNotEqualTo(tokens.refreshToken());
	}

	@Test
	@DisplayName("폐기된 Refresh Token을 재사용하면 401이고 해당 사용자의 토큰이 전부 무효화된다")
	void reusedRefreshTokenRevokesEverything() throws Exception {
		Tokens tokens = signupNewUser("재현");

		MvcResult first = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isOk())
				.andReturn();
		String rotated = first.getResponse().getCookie(REFRESH_COOKIE).getValue();

		// 이미 회전된 토큰을 다시 사용 → 탈취로 간주
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		// 정상적으로 회전된 토큰도 함께 무효화되어야 한다
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, rotated)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("쿠키 없이 재발급하면 401이다")
	void refreshWithoutCookieIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("로그아웃하면 204이고 쿠키가 만료되며 해당 Refresh Token은 더 이상 쓸 수 없다")
	void logoutRevokesRefreshToken() throws Exception {
		Tokens tokens = signupNewUser("재현");

		MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
						.header("Authorization", "Bearer " + tokens.accessToken())
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isNoContent())
				.andReturn();

		assertThat(result.getResponse().getCookie(REFRESH_COOKIE).getMaxAge()).isZero();

		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("토큰 없이 보호된 엔드포인트를 호출하면 401이다")
	void protectedEndpointRequiresToken() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("위조된 Access Token은 401이다")
	void forgedAccessTokenIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout")
						.header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged"))
				.andExpect(status().isUnauthorized());
	}

	// --- helpers ---

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder kakaoLogin() throws Exception {
		String body = objectMapper.writeValueAsString(
				java.util.Map.of("code", "auth-code", "redirectUri", "https://reused.app/oauth/callback"));
		return post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signup(
			String signupToken, String nickname) throws Exception {
		String body = objectMapper.writeValueAsString(
				java.util.Map.of("signupToken", signupToken, "nickname", nickname, "termsAgreed", true));
		return post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String signupTokenFromLogin() throws Exception {
		MvcResult result = mockMvc.perform(kakaoLogin()).andExpect(status().isOk()).andReturn();
		JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
		return json.get("signupToken").asString();
	}

	private Tokens signupNewUser(String nickname) throws Exception {
		String signupToken = signupTokenFromLogin();
		MvcResult result = mockMvc.perform(signup(signupToken, nickname))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(
				json.get("accessToken").asString(),
				result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
