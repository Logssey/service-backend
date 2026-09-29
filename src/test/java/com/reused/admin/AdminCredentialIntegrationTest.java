package com.reused.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"app.kakao.stub=true",
		"app.kakao.client-secret=credential-kakao-canary-7421",
		"spring.mail.password=credential-smtp-canary-7421",
		"app.chatbot.llm.gemini.api-key=credential-llm-canary-7421"
})
class AdminCredentialIntegrationTest {

	private static final String STATUS = "/api/v1/admin/credentials/status";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private StringRedisTemplate redis;

	@MockitoBean private OAuthProviderClient kakaoOAuthClient;
	@MockitoBean private AuthMailSender mailSender;

	private AdminTestClient client;
	private Member admin;
	private Member user;

	@BeforeEach
	void setUp() throws Exception {
		client = new AdminTestClient(mockMvc, objectMapper, jdbc, redis);
		client.reset();
		admin = client.signupAdmin("admin@example.com", "관리자");
		user = client.signup("user@example.com", "일반회원");
	}

	@Test
	void onlyMetadataIsReturnedAndNeverCached() throws Exception {
		var response = mockMvc.perform(get(STATUS).header("Authorization", AdminTestClient.bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.credentials.length()").value(7))
				.andExpect(jsonPath("$.credentials[0].service").value("JWT"))
				.andExpect(jsonPath("$.credentials[0].configured").value(true))
				.andExpect(jsonPath("$.credentials[0].source").value("APPLICATION_CONFIGURATION"))
				.andExpect(jsonPath("$.credentials[4].service").value("KAKAO_OAUTH"))
				.andExpect(jsonPath("$.credentials[4].configured").value(false))
				.andExpect(jsonPath("$.credentials[4].enabled").value(false))
				.andExpect(jsonPath("$.credentials[5].service").value("IMAGE_STORAGE"))
				.andExpect(jsonPath("$.credentials[5].source").value("DEFAULT_PROVIDER_CHAIN"))
				.andExpect(jsonPath("$.credentials[6].service").value("LLM"))
				.andExpect(jsonPath("$.credentials[6].configured").value(true))
				.andExpect(jsonPath("$.credentials[6].enabled").value(false))
				.andReturn().getResponse();

		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		assertThat(response.getContentAsString())
				.doesNotContain("credential-kakao-canary-7421", "credential-smtp-canary-7421",
						"credential-llm-canary-7421", "api-key", "password", "client-secret");
		assertThat(jdbc.queryForMap("SELECT actor_id, action, target_type, target_id, detail "
				+ "FROM audit_logs WHERE action = 'CREDENTIAL_STATUS_VIEW'"))
				.containsEntry("actor_id", admin.userId())
				.containsEntry("action", "CREDENTIAL_STATUS_VIEW")
				.containsEntry("target_type", null)
				.containsEntry("target_id", null)
				.containsEntry("detail", null);
	}

	@Test
	void anonymousAndMemberCannotReadCredentialStatus() throws Exception {
		mockMvc.perform(get(STATUS)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(STATUS).header("Authorization", AdminTestClient.bearer(user)))
				.andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CREDENTIAL_STATUS_VIEW'",
				Long.class)).isZero();
	}

	@Test
	void revokedAdministratorIsBlockedImmediately() throws Exception {
		jdbc.update("UPDATE users SET role = 'USER' WHERE user_id = ?", admin.userId());
		mockMvc.perform(get(STATUS).header("Authorization", AdminTestClient.bearer(admin)))
				.andExpect(status().isForbidden());
	}
}
