package com.reused.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.user.entity.UserRole;

/**
 * SecurityConfig의 공개·관리자 규칙과 {@link AdminAccessInterceptor}의 DB 재확인.
 *
 * <p>관리자 엔드포인트가 아직 없으므로 없는 경로({@link #ADMIN_PATH})로 확인한다. 모든 검사를 통과하면
 * 매핑이 없어 404, 막히면 403(역할 없음·정지) 또는 401(행 없음·탈퇴)이다. 관리자 토큰은 가입 → role 변경 → 재발급으로 얻는다(역할은 발급 시점 클레임).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesIntegrationTest {

	private static final String ADMIN_PATH = "/api/v1/admin/__no-such-endpoint__";
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
	private JwtTokenProvider tokenProvider;

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
	}

	// --- 관리자 경로 ---

	@Test
	@DisplayName("토큰 없이 관리자 경로를 부르면 401이다")
	void adminPathRequiresAuthentication() throws Exception {
		mockMvc.perform(get(ADMIN_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("USER 토큰으로 관리자 경로를 부르면 403 FORBIDDEN이다")
	void userTokenIsForbidden() throws Exception {
		String accessToken = signup("user@example.com", "일반회원").accessToken();

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("DB에서도 ADMIN인 관리자는 모든 검사를 통과해 매핑 단계(없는 경로 404)까지 간다")
	void activeAdminPasses() throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("ADMIN 토큰이 남아 있어도 DB에서 역할이 회수되었으면 403이다")
	void revokedAdminIsForbidden() throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");
		jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE user_id = 1");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@ParameterizedTest(name = "suspended_until = {0}")
	@ValueSource(strings = { "NULL", "now() + interval '1 day'" })
	@DisplayName("정지 중인 관리자(무기한 또는 기간 남음)는 403이다")
	void suspendedAdminIsForbidden(String suspendedUntil) throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = " + suspendedUntil
				+ " WHERE user_id = 1");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("정지 기간이 이미 지났으면 정지로 보지 않는다")
	void expiredSuspensionDoesNotBlock() throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() - interval '1 minute' "
				+ "WHERE user_id = 1");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("탈퇴한 관리자는 401이다(탈퇴 토큰 규칙, contracts §1.5)")
	void withdrawnAdminIsUnauthenticated() throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = 1");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("탈퇴 시각만 기록된 비정상 행도 탈퇴로 보아 401이다(User.isWithdrawn 기준)")
	void withdrawnAtOnlyAdminIsUnauthenticated() throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");
		jdbcTemplate.update("UPDATE users SET withdrawn_at = now() WHERE user_id = 1");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("정지 중인데 탈퇴 시각도 있으면 탈퇴를 먼저 보아 401이다")
	void withdrawnWhileSuspendedAdminIsUnauthenticated() throws Exception {
		String adminToken = adminToken("admin@example.com", "관리자");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = NULL, withdrawn_at = now() "
				+ "WHERE user_id = 1");

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("DB에 없는 사용자의 ADMIN 토큰은 401이다")
	void unknownUserAdminTokenIsUnauthenticated() throws Exception {
		String forgedAdmin = tokenProvider.issueAccessToken(999L, UserRole.ADMIN);

		mockMvc.perform(get(ADMIN_PATH).header("Authorization", "Bearer " + forgedAdmin))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- 공개 조회 경로 ---

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/notices", "/api/v1/notices/1", "/api/v1/users/1/profile",
			"/api/v1/users/1/listings", "/api/v1/users/1/reviews" })
	@DisplayName("공개 조회 경로는 토큰 없이 GET해도 401이 아니다")
	void publicReadPathsAreOpen(String path) throws Exception {
		MvcResult result = mockMvc.perform(get(path)).andReturn();

		assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
	}

	@Test
	@DisplayName("공개는 GET만이다. 같은 경로라도 쓰기는 인증이 필요하다")
	void publicPathsAreReadOnly() throws Exception {
		mockMvc.perform(post("/api/v1/notices"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/users/me"))
				.andExpect(status().isUnauthorized());
	}

	// --- helpers ---

	private Tokens signup(String email, String nickname) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", "hunter22!pw");
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		MvcResult result = mockMvc.perform(json(post("/api/v1/auth/email/signup"), body))
				.andExpect(status().isCreated())
				.andReturn();
		return new Tokens(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(),
				result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	/** 가입 → role=ADMIN → 재발급. AuthService.refresh는 DB의 role로 새 Access Token을 만든다. */
	private String adminToken(String email, String nickname) throws Exception {
		Tokens tokens = signup(email, nickname);
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = "
				+ "(SELECT user_id FROM user_identities WHERE provider = 'LOCAL' AND email = ?)", email);
		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asString();
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
