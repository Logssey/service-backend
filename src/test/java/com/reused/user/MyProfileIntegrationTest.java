package com.reused.user;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.user.entity.AuthProvider;

/**
 * 내 정보 조회 통합 테스트. 인증 수단(provider)·이메일·이메일 소유 확인 여부가 user_identities에서 오는지 검증한다.
 * 이메일을 입력한 소셜 계정의 경우는 SocialEmailIntegrationTest가 다룬다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MyProfileIntegrationTest {

	private static final String EMAIL = "user@example.com";

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
		jdbcTemplate.execute(
				"TRUNCATE notification_settings, user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willReturn("1234567890");
	}

	@Test
	@DisplayName("이메일 없이 온보딩한 카카오 계정은 provider=KAKAO, email=null, emailVerified=false이고 기본 상태와 가입 시각이 담긴다")
	void kakaoUserProfile() throws Exception {
		String accessToken = signupKakaoUser("재현");

		mockMvc.perform(me(accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(1))
				.andExpect(jsonPath("$.nickname").value("재현"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.suspendedUntil").doesNotExist())
				.andExpect(jsonPath("$.provider").value("KAKAO"))
				.andExpect(jsonPath("$.email").value(nullValue()))
				.andExpect(jsonPath("$.emailVerified").value(false))
				.andExpect(jsonPath("$.createdAt").isString());
	}

	@Test
	@DisplayName("이메일 계정은 provider=LOCAL, email=가입 이메일이고 소유 확인 전에는 false, 확인 뒤에는 true다")
	void localUserEmailVerifiedFollowsConfirmation() throws Exception {
		String accessToken = signupLocalUser("재현");

		mockMvc.perform(me(accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("LOCAL"))
				.andExpect(jsonPath("$.email").value(EMAIL))
				.andExpect(jsonPath("$.emailVerified").value(false));

		mockMvc.perform(json(post("/api/v1/auth/email/verification/confirm"), Map.of("code", lastVerificationCode()))
						.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().is2xxSuccessful());

		mockMvc.perform(me(accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.emailVerified").value(true));
	}

	@Test
	@DisplayName("본인 응답의 email은 본인 주소이고, 비밀번호 해시·제공자 회원번호 같은 인증 수단 내부 값은 없다")
	void credentialsAreNotExposed() throws Exception {
		String accessToken = signupLocalUser("재현");

		mockMvc.perform(me(accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(EMAIL))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.emailConsentAt").doesNotExist())
				.andExpect(jsonPath("$.providerUserId").doesNotExist());
	}

	@Test
	@DisplayName("토큰 없이 호출하면 401 UNAUTHENTICATED다")
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/users/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("탈퇴한 계정은 만료 전 Access Token이어도 401이다")
	void withdrawnUserIsUnauthenticated() throws Exception {
		String accessToken = signupKakaoUser("재현");
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now()");

		mockMvc.perform(me(accessToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- helpers ---

	private MockHttpServletRequestBuilder me(String accessToken) {
		return get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private String signupKakaoUser(String nickname) throws Exception {
		MvcResult login = mockMvc.perform(json(post("/api/v1/auth/oauth/kakao"),
						Map.of("code", "auth-code", "redirectUri", "https://reused.app/oauth/callback")))
				.andExpect(status().isOk())
				.andReturn();
		String signupToken = objectMapper.readTree(login.getResponse().getContentAsString())
				.get("signupToken").asString();

		MvcResult signup = mockMvc.perform(json(post("/api/v1/auth/signup"),
						Map.of("signupToken", signupToken, "nickname", nickname,
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(signup.getResponse().getContentAsString()).get("accessToken").asString();
	}

	private String signupLocalUser(String nickname) throws Exception {
		MvcResult signup = mockMvc.perform(json(post("/api/v1/auth/email/signup"),
						Map.of("email", EMAIL, "password", "hunter22!pw", "nickname", nickname,
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(signup.getResponse().getContentAsString()).get("accessToken").asString();
	}

	private String lastVerificationCode() {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(mailSender).sendVerificationCode(eq(EMAIL), captor.capture());
		return captor.getValue();
	}

}
