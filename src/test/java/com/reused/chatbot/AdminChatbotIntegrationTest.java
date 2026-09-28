package com.reused.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminChatbotIntegrationTest {

	private static final String ADMIN_CHATBOT = "/api/v1/admin/chatbot";
	private static final String SUGGESTED = "/api/v1/chatbot/suggested-questions";

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
		jdbc.update("UPDATE service_feature_flags SET enabled = true WHERE feature_key = 'CHATBOT'");
		admin = client.signupAdmin("admin@example.com", "관리자");
		user = client.signup("user@example.com", "일반회원");
	}

	@AfterEach
	void restoreSwitch() {
		jdbc.update("UPDATE service_feature_flags SET enabled = true WHERE feature_key = 'CHATBOT'");
	}

	@Test
	void administratorCanDisableAndEnableSuggestedQuestionsWithoutEnablingFreeInput() throws Exception {
		mockMvc.perform(get(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.adminEnabled").value(true))
				.andExpect(jsonPath("$.environmentEnabled").value(true))
				.andExpect(jsonPath("$.freeInputEnabled").value(false));

		mockMvc.perform(patch(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false));
		mockMvc.perform(get(SUGGESTED).header("Authorization", AdminTestClient.bearer(user)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

		mockMvc.perform(patch(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.freeInputEnabled").value(false));
		mockMvc.perform(get(SUGGESTED).header("Authorization", AdminTestClient.bearer(user)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/chatbot/messages").header("Authorization", AdminTestClient.bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
				.andExpect(status().isServiceUnavailable());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CHATBOT_STATUS_UPDATE'",
				Long.class)).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT detail->>'before' AS before, detail->>'after' AS after "
				+ "FROM audit_logs WHERE action = 'CHATBOT_STATUS_UPDATE' ORDER BY audit_log_id"))
				.containsExactly(Map.of("before", "true", "after", "false"),
						Map.of("before", "false", "after", "true"));
	}

	@Test
	void onlyCurrentAdministratorCanChangeTheSwitchAndInvalidRequestsDoNotChangeIt() throws Exception {
		mockMvc.perform(patch(ADMIN_CHATBOT).contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(patch(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		jdbc.update("UPDATE users SET role = 'USER' WHERE user_id = ?", admin.userId());
		mockMvc.perform(patch(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT enabled FROM service_feature_flags WHERE feature_key = 'CHATBOT'",
				Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CHATBOT_STATUS_UPDATE'",
				Long.class)).isZero();
	}

	@Test
	void repeatedSettingDoesNotWriteAnAuditEntry() throws Exception {
		mockMvc.perform(patch(ADMIN_CHATBOT).header("Authorization", AdminTestClient.bearer(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CHATBOT_STATUS_UPDATE'",
				Long.class)).isZero();
	}
}
