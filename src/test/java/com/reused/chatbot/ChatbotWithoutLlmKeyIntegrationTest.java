package com.reused.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.reused.chatbot.llm.GeminiLlmClient;
import com.reused.chatbot.llm.LlmClient;

/**
 * LLM API 키가 없는 실제 배선(테스트 설정은 키를 비워 둔다). 대역 없이 기본 공급자인 {@link GeminiLlmClient}가 뜨고,
 * 추천 질문은 동작하며 자유 입력만 503이다. 외부 대역 구성이 다른 통합 테스트와 같아 컨텍스트를 함께 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChatbotWithoutLlmKeyIntegrationTest {

	private static final String MESSAGES = "/api/v1/chatbot/messages";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private LlmClient llmClient;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	@Test
	@DisplayName("키가 없으면 기본 공급자인 Gemini 어댑터가 뜨지만 설정되지 않은 상태다")
	void adapterIsNotConfigured() {
		assertThat(llmClient).isInstanceOf(GeminiLlmClient.class);
		assertThat(llmClient.isConfigured()).isFalse();
	}

	@Test
	@DisplayName("자유 입력은 503이고 횟수를 세지 않는다. 추천 질문 조회·선택은 그대로 동작한다")
	void onlyFreeTextIsUnavailable() throws Exception {
		String token = signup();

		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		assertThat(redisTemplate.hasKey("reused:chatbot:rate:1")).isFalse();

		mockMvc.perform(get("/api/v1/chatbot/suggested-questions").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5));
		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("PREDEFINED"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'EXTERNAL_LLM'",
				Long.class)).isZero();
	}

	private MockHttpServletRequestBuilder send(String accessToken, Map<String, ?> body) {
		return post(MESSAGES).header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body));
	}

	private String signup() throws Exception {
		MvcResult signup = mockMvc.perform(post("/api/v1/auth/email/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("email", "user@example.com",
								"password", "hunter22!pw", "nickname", "재현",
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true))))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(signup.getResponse().getContentAsString()).get("accessToken").asString();
	}

}
