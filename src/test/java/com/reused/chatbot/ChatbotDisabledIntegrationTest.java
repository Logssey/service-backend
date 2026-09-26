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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.chatbot.llm.LlmClient;

/**
 * 챗봇 비활성화 스위치(ADR-003, FR-AI-008). 꺼져 있으면 두 엔드포인트 모두 503이다.
 * 인증과 요청 형식 검사는 스위치보다 먼저다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.chatbot.enabled=false")
class ChatbotDisabledIntegrationTest {

	private static final String SUGGESTED_QUESTIONS = "/api/v1/chatbot/suggested-questions";
	private static final String MESSAGES = "/api/v1/chatbot/messages";
	private static final String DISABLED_MESSAGE = "현재 챗봇을 이용할 수 없습니다.";

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

	/** LLM은 설정된 상태로 둔다. 그래야 자유 입력의 503이 미설정 가드가 아니라 스위치에서 나온 것임을 가릴 수 있다. */
	@MockitoBean
	private LlmClient llmClient;

	@BeforeEach
	void resetState() {
		given(llmClient.isConfigured()).willReturn(true);
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	@Test
	@DisplayName("추천 질문 조회는 503 SERVICE_UNAVAILABLE이다")
	void listIsUnavailable() throws Exception {
		String token = signup();

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", "Bearer " + token))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.message").value(DISABLED_MESSAGE));
	}

	@Test
	@DisplayName("추천 질문 선택과 자유 입력 모두 503이고 호출 횟수를 세지 않으며 외부 연동 기록도 없다")
	void sendIsUnavailable() throws Exception {
		String token = signup();

		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.message").value(DISABLED_MESSAGE));
		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.message").value(DISABLED_MESSAGE));

		assertThat(redisTemplate.hasKey("reused:chatbot:rate:1")).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'EXTERNAL_LLM'",
				Long.class)).isZero();
		verify(llmClient, never()).complete(any());
	}

	@Test
	@DisplayName("요청 형식 오류는 스위치보다 먼저 400이다")
	void invalidInputIsCheckedFirst() throws Exception {
		String token = signup();

		mockMvc.perform(send(token, Map.of("questionId", 1, "message", "거래 방법")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("토큰이 없거나 탈퇴 회원이면 스위치와 무관하게 401이다")
	void authenticationIsCheckedFirst() throws Exception {
		mockMvc.perform(get(SUGGESTED_QUESTIONS))
				.andExpect(status().isUnauthorized());

		String token = signup();
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now()");

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
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
