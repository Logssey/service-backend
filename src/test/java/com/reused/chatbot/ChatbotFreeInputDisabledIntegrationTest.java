package com.reused.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.chatbot.config.ChatbotProperties;
import com.reused.chatbot.llm.LlmClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChatbotFreeInputDisabledIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private StringRedisTemplate redisTemplate;
	@Autowired private ChatbotProperties properties;

	@MockitoBean private LlmClient llmClient;
	@MockitoBean private OAuthProviderClient kakaoOAuthClient;
	@MockitoBean private AuthMailSender mailSender;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(llmClient.isConfigured()).willReturn(true);
	}

	@Test
	void configuredLlmCannotReceiveFreeInputWhileSuggestedQuestionsStillWork() throws Exception {
		assertThat(properties.freeInputEnabled()).isFalse();
		String token = signup();

		mockMvc.perform(post("/api/v1/chatbot/messages")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "제 이름과 주소를 알려줄게요"))))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

		verify(llmClient, never()).complete(any());
		assertThat(redisTemplate.hasKey("reused:chatbot:rate:1")).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'EXTERNAL_LLM'",
				Long.class)).isZero();

		mockMvc.perform(get("/api/v1/chatbot/suggested-questions")
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5));
		mockMvc.perform(post("/api/v1/chatbot/messages")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionId\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("PREDEFINED"));
		verify(llmClient, never()).complete(any());
	}

	private String signup() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/email/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", "user@example.com",
						"password", "hunter22!pw", "nickname", "재현",
						"termsOfServiceAgreed", true, "privacyPolicyAgreed", true))))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
	}
}
