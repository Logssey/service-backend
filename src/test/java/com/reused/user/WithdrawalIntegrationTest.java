package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.reused.user.entity.AuthProvider;

/**
 * 회원 탈퇴({@code DELETE /users/me}). 탈퇴 처리는 upstream {@code UserProfileLifecycleService}가 하고,
 * 이 테스트는 B 인증(재가입, 로그인, 비밀번호 변경 401)과의 결합을 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalIntegrationTest {

	private static final String URL = "/api/v1/users/me";
	private static final String REFRESH_COOKIE = "refresh_token";
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
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notifications, notification_settings, reports, blocks, "
				+ "user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		// 인가 코드를 그대로 카카오 회원번호로 쓴다. 코드만 바꿔 여러 회원을 만든다.
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	// --- 정상 ---

	@Test
	@DisplayName("탈퇴하면 204와 만료된 Refresh 쿠키를 받고 식별 정보가 지워진다")
	void withdrawsAndClearsIdentifyingData() throws Exception {
		Tokens tokens = signupLocalUser("user@example.com", "재현");
		jdbcTemplate.update("UPDATE users SET bio = '소개', profile_image_url = 'profiles/1/a.png'");

		MvcResult result = mockMvc.perform(withdraw(tokens.accessToken()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""))
				.andReturn();

		String setCookie = result.getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).startsWith(REFRESH_COOKIE + "=;").contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly");

		Map<String, Object> user = jdbcTemplate.queryForMap(
				"SELECT nickname, status, withdrawn_at, bio, profile_image_url, updated_at FROM users");
		assertThat(user.get("nickname")).isEqualTo("탈퇴회원#1");
		assertThat(user.get("status")).isEqualTo("WITHDRAWN");
		assertThat(user.get("withdrawn_at")).isNotNull();
		assertThat(user.get("bio")).isNull();
		assertThat(user.get("profile_image_url")).isNull();
		assertThat(user.get("updated_at")).isNotNull();
		assertThat(count("user_identities")).isZero();
		// 알림 설정 행은 남긴다(회원 행이 남으므로 CASCADE 대상이 아니다).
		assertThat(count("notification_settings")).isEqualTo(1);
	}

	@Test
	@DisplayName("모든 기기의 Refresh Token이 폐기되고 남은 Access Token으로는 아무것도 할 수 없다")
	void revokesSessionsAndOldTokensStopWorking() throws Exception {
		Tokens tokens = signupLocalUser("user@example.com", "재현");
		String secondDevice = loginRefreshToken("user@example.com");
		assertThat(redisTemplate.keys("reused:auth:refresh:1:*")).hasSize(2);

		mockMvc.perform(withdraw(tokens.accessToken())).andExpect(status().isNoContent());

		assertThat(redisTemplate.keys("reused:auth:refresh:1:*")).isEmpty();
		for (String refreshToken : List.of(tokens.refreshToken(), secondDevice)) {
			mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, refreshToken)))
					.andExpect(status().isUnauthorized());
		}
		String bearer = "Bearer " + tokens.accessToken();
		mockMvc.perform(get(URL).header("Authorization", bearer))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(withdraw(tokens.accessToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(json(patch(URL), Map.of("bio", "소개")).header("Authorization", bearer))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(json(patch(URL + "/password"), Map.of("currentPassword", PASSWORD, "newPassword", "hunter33!pw"))
						.header("Authorization", bearer))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(login("user@example.com")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("같은 이메일로 다시 가입하면 새 계정이 되고 옛 닉네임도 다시 쓸 수 있다(ADR-018)")
	void emailCanSignUpAgain() throws Exception {
		Tokens tokens = signupLocalUser("user@example.com", "재현");
		mockMvc.perform(withdraw(tokens.accessToken())).andExpect(status().isNoContent());

		mockMvc.perform(signupRequest("user@example.com", "재현"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.userId").value(2))
				.andExpect(jsonPath("$.user.nickname").value("재현"));
	}

	@Test
	@DisplayName("카카오 계정은 탈퇴 뒤 같은 회원번호로 로그인하면 가입 절차로 가고 새 계정이 된다")
	void kakaoAccountCanSignUpAgain() throws Exception {
		String accessToken = signupKakaoUser("111", "카카오유저");

		mockMvc.perform(withdraw(accessToken)).andExpect(status().isNoContent());

		String signupToken = kakaoSignupToken("111");
		mockMvc.perform(json(post("/api/v1/auth/signup"), Map.of("signupToken", signupToken, "nickname", "카카오유저",
						"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.userId").value(2));
		assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM user_identities", Long.class)).isEqualTo(2L);
	}

	@Test
	@DisplayName("이용정지 회원도 탈퇴할 수 있다")
	void suspendedUserCanWithdraw() throws Exception {
		Tokens tokens = signupLocalUser("user@example.com", "재현");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() + interval '7 days'");

		mockMvc.perform(withdraw(tokens.accessToken())).andExpect(status().isNoContent());

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM users", String.class)).isEqualTo("WITHDRAWN");
	}

	@Test
	@DisplayName("다른 회원은 영향을 받지 않는다")
	void otherUsersAreUntouched() throws Exception {
		Tokens tokens = signupLocalUser("user@example.com", "재현");
		Tokens other = signupLocalUser("second@example.com", "민지");

		mockMvc.perform(withdraw(tokens.accessToken())).andExpect(status().isNoContent());

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM users WHERE user_id = 2", String.class))
				.isEqualTo("ACTIVE");
		assertThat(redisTemplate.keys("reused:auth:refresh:2:*")).hasSize(1);
		mockMvc.perform(get(URL).header("Authorization", "Bearer " + other.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("민지"));
	}

	// --- 401 ---

	@Test
	@DisplayName("토큰이 없으면 401이다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(delete(URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("이미 탈퇴한 회원(상태값만 있는 비정상 행 포함)의 토큰은 401이다")
	void alreadyWithdrawnIsUnauthenticated() throws Exception {
		Tokens tokens = signupLocalUser("user@example.com", "재현");
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now()");

		mockMvc.perform(withdraw(tokens.accessToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertThat(count("user_identities")).isEqualTo(1);
	}

	// --- helpers ---

	private MockHttpServletRequestBuilder withdraw(String accessToken) {
		return delete(URL).header("Authorization", "Bearer " + accessToken);
	}

	private MockHttpServletRequestBuilder login(String email) {
		return json(post("/api/v1/auth/email/login"), Map.of("email", email, "password", PASSWORD));
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
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

	private Tokens signupLocalUser(String email, String nickname) throws Exception {
		MvcResult result = mockMvc.perform(signupRequest(email, nickname))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(body.get("accessToken").asString(), result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	private String loginRefreshToken(String email) throws Exception {
		MvcResult result = mockMvc.perform(login(email)).andExpect(status().isOk()).andReturn();
		return result.getResponse().getCookie(REFRESH_COOKIE).getValue();
	}

	private String kakaoSignupToken(String providerUserId) throws Exception {
		MvcResult login = mockMvc.perform(json(post("/api/v1/auth/oauth/kakao"),
						Map.of("code", providerUserId, "redirectUri", "https://reused.app/oauth/callback")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SIGNUP_REQUIRED"))
				.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString()).get("signupToken").asString();
	}

	private String signupKakaoUser(String providerUserId, String nickname) throws Exception {
		MvcResult signup = mockMvc.perform(json(post("/api/v1/auth/signup"),
						Map.of("signupToken", kakaoSignupToken(providerUserId), "nickname", nickname,
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(signup.getResponse().getContentAsString()).get("accessToken").asString();
	}

	private long count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
