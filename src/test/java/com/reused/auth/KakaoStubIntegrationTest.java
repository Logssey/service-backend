package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.KakaoApiClient;
import com.reused.auth.client.KakaoStubClient;

/**
 * local 프로파일의 카카오 대역. 대역을 목으로 바꾸지 않고 실제 빈으로 돌린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class KakaoStubIntegrationTest {

	private static final String STUB_CODE = "local-1a2b3c4d";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ApplicationContext context;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute(
				"TRUNCATE notification_settings, user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
	}

	@Test
	@DisplayName("local 프로파일에서는 대역만 뜨고 실제 카카오 클라이언트는 꺼진다")
	void onlyStubIsRegistered() {
		assertThat(context.getBeansOfType(KakaoStubClient.class)).hasSize(1);
		assertThat(context.getBeansOfType(KakaoApiClient.class)).isEmpty();
	}

	@Test
	@DisplayName("인가 코드가 그대로 회원번호가 되고, 같은 코드로 다시 들어오면 같은 계정으로 로그인된다")
	void codeBecomesProviderUserId() throws Exception {
		MvcResult first = mockMvc.perform(oauthLogin(STUB_CODE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SIGNUP_REQUIRED"))
				.andReturn();
		String signupToken = objectMapper.readTree(first.getResponse().getContentAsString())
				.get("signupToken").asString();

		mockMvc.perform(json(post("/api/v1/auth/signup"), Map.of("signupToken", signupToken, "nickname", "재현",
						"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated());

		String providerUserId = jdbcTemplate.queryForObject(
				"SELECT provider_user_id FROM user_identities WHERE provider = 'KAKAO'", String.class);
		assertThat(providerUserId).isEqualTo(STUB_CODE);

		mockMvc.perform(oauthLogin(STUB_CODE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOGIN"))
				.andExpect(jsonPath("$.user.nickname").value("재현"));
	}

	@Test
	@DisplayName("255자를 넘는 코드는 400 INVALID_INPUT이다")
	void tooLongCodeIsRejected() throws Exception {
		mockMvc.perform(oauthLogin("a".repeat(256)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	private MockHttpServletRequestBuilder oauthLogin(String code) {
		return json(post("/api/v1/auth/oauth/kakao"),
				Map.of("code", code, "redirectUri", "http://localhost:5173/oauth/callback"));
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

}
