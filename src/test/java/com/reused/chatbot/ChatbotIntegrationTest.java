package com.reused.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import com.reused.auth.token.JwtTokenProvider;
import com.reused.chatbot.catalog.SuggestedQuestion;
import com.reused.chatbot.config.ChatbotExecutor;
import com.reused.chatbot.config.ChatbotProperties;
import com.reused.chatbot.llm.LlmCallException;
import com.reused.chatbot.llm.LlmClient;
import com.reused.chatbot.llm.LlmReply;
import com.reused.chatbot.llm.LlmRequest;
import com.reused.chatbot.prompt.ChatbotPrompt;
import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserRole;

/**
 * 챗봇 두 엔드포인트 통합 테스트. LLM은 대역이고 DB·Redis는 실제 컨테이너다.
 *
 * <p>전체 제한 시간을 1초로 줄여 시간 초과를 빠르게 확인한다. 나머지 대역 응답은 즉시 돌아온다.
 * 외부 연동 기록(audit_logs)은 요청이 끝난 뒤 직접 조회한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.chatbot.llm.timeout=1s", "app.chatbot.free-input-enabled=true"})
class ChatbotIntegrationTest {

	private static final String PASSWORD = "hunter22!pw";
	private static final String NICKNAME = "재현";
	private static final String EMAIL = "user@example.com";
	private static final String RATE_KEY_PREFIX = "reused:chatbot:rate:";
	private static final String SUGGESTED_QUESTIONS = "/api/v1/chatbot/suggested-questions";
	private static final String MESSAGES = "/api/v1/chatbot/messages";
	private static final String TRADE_FLOW_ANSWER = "구매자가 거래를 요청하면 판매자가 승인하고, 직거래 후 구매자가 완료를 확정합니다.";
	private static final String OUT_OF_SCOPE_ANSWER = "서비스 이용과 관련된 질문에만 답변할 수 있습니다.";

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

	@Autowired
	private ChatbotExecutor chatbotExecutor;

	@Autowired
	private ChatbotProperties chatbotProperties;

	@MockitoBean
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
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		given(llmClient.isConfigured()).willReturn(true);
	}

	// --- GET /chatbot/suggested-questions ---

	@Test
	@DisplayName("추천 질문 5개를 questionId 오름차순 배열 그대로(페이지 래퍼 없이) 돌려준다")
	void listsSuggestedQuestions() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$[*].questionId").value(contains(1, 2, 3, 4, 5)))
				.andExpect(jsonPath("$[0].question").value("거래는 어떻게 진행되나요?"))
				.andExpect(jsonPath("$[1].question").value("신고는 어떻게 하나요?"))
				.andExpect(jsonPath("$[2].question").value("후기는 언제 작성할 수 있나요?"))
				.andExpect(jsonPath("$[3].question").value("계정을 삭제하고 싶어요"))
				.andExpect(jsonPath("$[4].question").value("환불 정책이 궁금해요"))
				.andExpect(jsonPath("$[0].answer").doesNotExist())
				.andExpect(jsonPath("$.items").doesNotExist());
	}

	@Test
	@DisplayName("추천 질문 조회는 호출 제한 횟수에 들어가지 않고 LLM도 부르지 않는다")
	void listingDoesNotCountTowardsRateLimit() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		for (int i = 0; i < 7; i++) {
			mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", bearer(token)))
					.andExpect(status().isOk());
		}

		assertThat(redisTemplate.hasKey(RATE_KEY_PREFIX + 1)).isFalse();
		verify(llmClient, never()).complete(any());
	}

	@Test
	@DisplayName("추천 질문 조회: 토큰이 없으면 401 UNAUTHENTICATED다")
	void listRequiresToken() throws Exception {
		mockMvc.perform(get(SUGGESTED_QUESTIONS))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("추천 질문 조회: 서명이 틀린 토큰은 401이다")
	void listRejectsMalformedToken() throws Exception {
		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("추천 질문 조회: 탈퇴한 회원의 만료 전 토큰은 401이다")
	void listRejectsWithdrawnUser() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		withdraw(1L);

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", bearer(token)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("추천 질문 조회: DB에 없는 회원의 토큰은 401이다")
	void listRejectsUnknownUser() throws Exception {
		String token = tokenProvider.issueAccessToken(999L, UserRole.USER);

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", bearer(token)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("추천 질문 조회: 이용정지 회원도 쓸 수 있다")
	void listAllowsSuspendedUser() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		suspend(1L);

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5));
	}

	@Test
	@DisplayName("추천 질문 조회: 관리자 토큰도 쓸 수 있다")
	void listAllowsAdmin() throws Exception {
		signup(EMAIL, NICKNAME);
		String adminToken = tokenProvider.issueAccessToken(1L, UserRole.ADMIN);

		mockMvc.perform(get(SUGGESTED_QUESTIONS).header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk());
	}

	// --- POST /chatbot/messages: 추천 질문 ---

	@Test
	@DisplayName("추천 질문을 고르면 사전 정의 답변(PREDEFINED)을 돌려주고 LLM을 부르지 않으며 외부 연동 기록도 없다")
	void predefinedAnswer() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value(TRADE_FLOW_ANSWER))
				.andExpect(jsonPath("$.source").value("PREDEFINED"));

		verify(llmClient, never()).complete(any());
		assertThat(llmAuditRows()).isEmpty();
	}

	@Test
	@DisplayName("추천 질문 목록의 모든 questionId에 사전 정의 답변이 있다")
	void everySuggestedQuestionHasAnswer() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		for (SuggestedQuestion question : SuggestedQuestion.all()) {
			redisTemplate.delete(RATE_KEY_PREFIX + 1);
			mockMvc.perform(send(token, Map.of("questionId", question.id())))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.answer").value(question.answer()))
					.andExpect(jsonPath("$.source").value("PREDEFINED"));
		}
	}

	@Test
	@DisplayName("없는 questionId는 404 NOT_FOUND다. 호출 제한 횟수에는 들어간다")
	void unknownQuestionIsNotFound() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of("questionId", 999)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("추천 질문을 찾을 수 없습니다."));

		assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + 1)).isEqualTo("1");
	}

	// --- POST /chatbot/messages: 400 ---

	@Test
	@DisplayName("questionId와 message를 둘 다 지정하면 400 INVALID_INPUT이다")
	void bothFieldsAreRejected() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of("questionId", 1, "message", "거래 방법")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("questionId와 message 중 하나만 지정해야 합니다."));
	}

	@Test
	@DisplayName("빈 문자열 message도 지정한 것이라 questionId와 함께 보내면 400이다")
	void emptyMessageWithQuestionIsBothSpecified() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of("questionId", 1, "message", "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("questionId와 message 중 하나만 지정해야 합니다."));
	}

	@Test
	@DisplayName("두 필드가 모두 없거나 null이면 400 INVALID_INPUT이다")
	void missingBothFieldsIsRejected() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		Map<String, Object> nulls = new HashMap<>();
		nulls.put("questionId", null);
		nulls.put("message", null);

		mockMvc.perform(send(token, Map.of()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("questionId와 message 중 하나만 지정해야 합니다."));
		mockMvc.perform(send(token, nulls))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("message가 500자를 넘으면 400이고, 정확히 500자는 받는다")
	void messageLengthLimit() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("안내"));

		mockMvc.perform(send(token, Map.of("message", "가".repeat(501))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("message: 500자 이하로 입력해 주세요."));
		mockMvc.perform(send(token, Map.of("message", "가".repeat(500))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("LLM"));
	}

	@Test
	@DisplayName("공백뿐인 message는 400 INVALID_INPUT이다")
	void blankMessageIsRejected() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of("message", "   \n\t ")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("질문을 입력해 주세요."));
		verify(llmClient, never()).complete(any());
	}

	@ParameterizedTest
	@ValueSource(longs = { 0L, -1L })
	@DisplayName("questionId가 양수가 아니면 400 INVALID_INPUT이다")
	void nonPositiveQuestionIdIsRejected(long questionId) throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of("questionId", questionId)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("깨진 JSON과 타입이 틀린 questionId는 400이고 내부 타입 이름을 싣지 않는다")
	void unreadableBodyIsRejected() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(post(MESSAGES).header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content("{\"questionId\":"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(post(MESSAGES).header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content("{\"questionId\":\"abc\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
	}

	@Test
	@DisplayName("400으로 끝난 요청은 호출 제한 횟수에 들어가지 않는다")
	void invalidRequestsAreNotCounted() throws Exception {
		String token = signup(EMAIL, NICKNAME);

		mockMvc.perform(send(token, Map.of()))
				.andExpect(status().isBadRequest());
		mockMvc.perform(send(token, Map.of("message", "  ")))
				.andExpect(status().isBadRequest());
		mockMvc.perform(send(token, Map.of("message", "가".repeat(501))))
				.andExpect(status().isBadRequest());

		assertThat(redisTemplate.hasKey(RATE_KEY_PREFIX + 1)).isFalse();
	}

	// --- POST /chatbot/messages: 인증 ---

	@Test
	@DisplayName("메시지 전송: 토큰이 없으면 401 UNAUTHENTICATED다")
	void sendRequiresToken() throws Exception {
		mockMvc.perform(post(MESSAGES).contentType(MediaType.APPLICATION_JSON).content("{\"questionId\":1}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("메시지 전송: 탈퇴한 회원의 토큰은 401이고 LLM을 부르지 않으며 호출 횟수도 세지 않는다")
	void sendRejectsWithdrawnUser() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		withdraw(1L);

		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(send(token, Map.of("message", "거래 방법 알려 주세요")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		verify(llmClient, never()).complete(any());
		assertThat(redisTemplate.hasKey(RATE_KEY_PREFIX + 1)).isFalse();
		assertThat(llmAuditRows()).isEmpty();
	}

	@Test
	@DisplayName("메시지 전송: DB에 없는 회원의 토큰은 401이다")
	void sendRejectsUnknownUser() throws Exception {
		String token = tokenProvider.issueAccessToken(999L, UserRole.USER);

		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("메시지 전송: 이용정지 회원도 추천 질문과 자유 입력을 모두 쓸 수 있다")
	void sendAllowsSuspendedUser() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		suspend(1L);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("안내"));

		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("PREDEFINED"));
		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("LLM"));
	}

	// --- POST /chatbot/messages: 자유 입력 ---

	@Test
	@DisplayName("자유 입력은 LLM 답변(LLM)을 돌려주고, 요청에는 정적 지시문과 입력만 담기며 결과를 EXTERNAL_LLM으로 기록한다")
	void llmAnswer() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("  판매자가 승인하면 예약중이 됩니다.  "));

		mockMvc.perform(send(token, Map.of("message", "  거래 승인 후에는 어떻게 되나요?  ")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value("판매자가 승인하면 예약중이 됩니다."))
				.andExpect(jsonPath("$.source").value("LLM"));

		LlmRequest request = capturedRequest();
		assertThat(request.userMessage()).isEqualTo("거래 승인 후에는 어떻게 되나요?");
		assertThat(request.systemPrompt()).isEqualTo(ChatbotPrompt.SYSTEM);
		assertThat(request.systemPrompt()).doesNotContain(NICKNAME, EMAIL);

		Map<String, Object> row = singleLlmAuditRow();
		assertThat(row.get("actor_id")).isEqualTo(1L);
		assertThat(row.get("target_type")).isEqualTo("EXTERNAL");
		assertThat(row.get("target_id")).isNull();
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("outcome")).isEqualTo("ANSWERED");
		assertThat(row.get("latency_ms")).isNotNull();
		assertThat(row.get("ip")).isEqualTo("127.0.0.1");
		// 질문·답변 원문은 기록하지 않는다.
		assertThat((String) row.get("detail")).doesNotContain("거래 승인", "예약중", NICKNAME, EMAIL);
	}

	@Test
	@DisplayName("입력 속 이메일·휴대폰 번호·긴 숫자열은 가린 뒤 LLM에 보낸다")
	void personalDataIsMaskedBeforeLlm() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("안내"));

		mockMvc.perform(send(token, Map.of("message",
						"연락은 buyer.kim+1@mail.example.com 이나 010-1234-5678, 계좌 110-123-456789 로 주세요")))
				.andExpect(status().isOk());

		assertThat(capturedRequest().userMessage())
				.isEqualTo("연락은 [이메일] 이나 [전화번호], 계좌 [번호] 로 주세요");
	}

	@Test
	@DisplayName("모델이 범위 밖 표식을 내면 고정 문구(OUT_OF_SCOPE)로 바꾸고 표식 뒤의 말은 내보내지 않는다")
	void outOfScopeMarker() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any()))
				.willReturn(LlmReply.answered("OUT_OF_SCOPE"))
				.willReturn(LlmReply.answered(" OUT_OF_SCOPE: 날씨는 알려 드릴 수 없습니다"));

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(send(token, Map.of("message", "오늘 날씨 어때?")))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.answer").value(OUT_OF_SCOPE_ANSWER))
					.andExpect(jsonPath("$.source").value("OUT_OF_SCOPE"));
		}

		assertThat(llmAuditRows()).extracting(row -> row.get("outcome"), row -> row.get("result"))
				.containsOnly(tuple("OUT_OF_SCOPE", "SUCCESS"));
	}

	@Test
	@DisplayName("공급자 안전 정책이 거절하면(refusal) 범위 밖 안내로 답한다")
	void providerRefusalIsOutOfScope() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.refusal());

		mockMvc.perform(send(token, Map.of("message", "위험한 질문")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value(OUT_OF_SCOPE_ANSWER))
				.andExpect(jsonPath("$.source").value("OUT_OF_SCOPE"));

		Map<String, Object> row = singleLlmAuditRow();
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("outcome")).isEqualTo("REFUSED");
	}

	@Test
	@DisplayName("답변이 1000자를 넘으면 1000자에서 자른다")
	void longAnswerIsTruncated() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("가".repeat(1500)));

		MvcResult result = mockMvc.perform(send(token, Map.of("message", "길게 설명해 주세요")))
				.andExpect(status().isOk())
				.andReturn();

		String answer = objectMapper.readTree(result.getResponse().getContentAsString()).get("answer").asString();
		assertThat(answer).hasSize(chatbotProperties.llm().maxAnswerLength()).isEqualTo("가".repeat(1000));
	}

	@Test
	@DisplayName("모델 답변이 비어 있으면 502 EXTERNAL_SERVICE_ERROR이고 FAILURE(EMPTY)로 기록한다")
	void emptyAnswerIsBadGateway() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("  \n "));

		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"));

		Map<String, Object> row = singleLlmAuditRow();
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("outcome")).isEqualTo("EMPTY");
	}

	@Test
	@DisplayName("LLM API 실패는 502이고 공급자 정보 없이 안내하며 실패 종류와 HTTP 상태를 기록한다")
	void llmFailureIsBadGateway() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any()))
				.willThrow(new LlmCallException(LlmCallException.Kind.SERVER_ERROR, 529, null));

		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"))
				.andExpect(jsonPath("$.message").value("챗봇 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."));

		Map<String, Object> row = singleLlmAuditRow();
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("outcome")).isEqualTo("SERVER_ERROR");
		assertThat(row.get("http_status")).isEqualTo("529");
		verify(llmClient, times(1)).complete(any());
	}

	@Test
	@DisplayName("연결 실패와 분류되지 않은 어댑터 예외도 502다")
	void connectionAndUnexpectedFailuresAreBadGateway() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any()))
				.willThrow(new LlmCallException(LlmCallException.Kind.CONNECTION, null, null))
				.willThrow(new IllegalStateException("boom"));

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(send(token, Map.of("message", "거래 방법")))
					.andExpect(status().isBadGateway())
					.andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"));
		}

		assertThat(llmAuditRows()).extracting(row -> row.get("outcome")).containsExactly("CONNECTION", "ERROR");
	}

	@Test
	@DisplayName("LLM이 전체 제한 시간 안에 답하지 않으면 작업을 끊고 502를 낸다(TIMEOUT)")
	void llmTimeoutIsBadGateway() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		CountDownLatch interrupted = new CountDownLatch(1);
		given(llmClient.complete(any())).willAnswer(invocation -> {
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException e) {
				interrupted.countDown();
				throw e;
			}
			return LlmReply.answered("늦은 답변");
		});

		long startedAt = System.nanoTime();
		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
		assertThat(interrupted.await(3, TimeUnit.SECONDS)).as("시간 초과 뒤 풀 작업이 인터럽트된다").isTrue();
		Map<String, Object> row = singleLlmAuditRow();
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("outcome")).isEqualTo("TIMEOUT");
	}

	/**
	 * 실행 슬롯을 먼저 모두 채우고(작업이 시작된 것을 확인) 그다음 대기열을 채운다. 한꺼번에 넣으면 쉬고 있던 스레드가
	 * 대기열에서 꺼내기 전에 대기열이 차서 준비 단계에서 거절될 수 있다.
	 */
	@Test
	@DisplayName("챗봇 전용 풀이 가득 차면 기다리지 않고 503 SERVICE_UNAVAILABLE이다(REJECTED)")
	void saturatedPoolIsServiceUnavailable() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		int poolSize = chatbotProperties.executor().poolSize();
		int queueCapacity = chatbotProperties.executor().queueCapacity();
		CountDownLatch running = new CountDownLatch(poolSize);
		CountDownLatch release = new CountDownLatch(1);
		List<Future<Boolean>> blockers = new ArrayList<>();
		try {
			for (int i = 0; i < poolSize; i++) {
				blockers.add(chatbotExecutor.submit(() -> {
					running.countDown();
					return release.await(10, TimeUnit.SECONDS);
				}));
			}
			assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
			for (int i = 0; i < queueCapacity; i++) {
				blockers.add(chatbotExecutor.submit(() -> release.await(10, TimeUnit.SECONDS)));
			}

			mockMvc.perform(send(token, Map.of("message", "거래 방법")))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
					.andExpect(jsonPath("$.message").value("요청이 많아 잠시 후 다시 시도해 주세요."));
		}
		finally {
			release.countDown();
		}
		for (Future<Boolean> blocker : blockers) {
			blocker.get(5, TimeUnit.SECONDS);
		}

		verify(llmClient, never()).complete(any());
		Map<String, Object> row = singleLlmAuditRow();
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("outcome")).isEqualTo("REJECTED");
	}

	@Test
	@DisplayName("LLM 설정(API 키)이 없으면 자유 입력만 503이고 추천 질문은 동작한다. 503은 횟수에 들어가지 않는다")
	void unconfiguredLlmOnlyBlocksFreeText() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.isConfigured()).willReturn(false);

		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
		assertThat(redisTemplate.hasKey(RATE_KEY_PREFIX + 1)).isFalse();

		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("PREDEFINED"));
		verify(llmClient, never()).complete(any());
	}

	// --- POST /chatbot/messages: 호출 제한 ---

	@Test
	@DisplayName("같은 사용자가 1분에 5번까지 쓰고 6번째는 429 RATE_LIMITED다. 키에는 1분 TTL이 있다")
	void rateLimitPerMinute() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		given(llmClient.complete(any())).willReturn(LlmReply.answered("안내"));

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(send(token, Map.of("questionId", 1))).andExpect(status().isOk());
		}
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(send(token, Map.of("message", "거래 방법"))).andExpect(status().isOk());
		}

		mockMvc.perform(send(token, Map.of("message", "거래 방법")))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.message").value("챗봇은 1분에 5번까지 이용할 수 있습니다. 잠시 후 다시 시도해 주세요."));
		mockMvc.perform(send(token, Map.of("questionId", 1)))
				.andExpect(status().isTooManyRequests());

		verify(llmClient, times(2)).complete(any());
		Long ttl = redisTemplate.getExpire(RATE_KEY_PREFIX + 1);
		assertThat(ttl).isBetween(1L, 60L);
	}

	@Test
	@DisplayName("호출 제한은 사용자마다 따로 센다")
	void rateLimitIsPerUser() throws Exception {
		String first = signup(EMAIL, NICKNAME);
		String second = signup("other@example.com", "민지");

		for (int i = 0; i < 5; i++) {
			mockMvc.perform(send(first, Map.of("questionId", 1))).andExpect(status().isOk());
		}
		mockMvc.perform(send(first, Map.of("questionId", 1))).andExpect(status().isTooManyRequests());

		mockMvc.perform(send(second, Map.of("questionId", 1))).andExpect(status().isOk());
		assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + 2)).isEqualTo("1");
	}

	@Test
	@DisplayName("TTL 없이 남은 카운터 키를 만나면 TTL을 다시 건다(영구 차단 방지)")
	void rateKeyWithoutTtlIsRepaired() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		redisTemplate.opsForValue().set(RATE_KEY_PREFIX + 1, "2");

		mockMvc.perform(send(token, Map.of("questionId", 1))).andExpect(status().isOk());

		assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + 1)).isEqualTo("3");
		assertThat(redisTemplate.getExpire(RATE_KEY_PREFIX + 1)).isBetween(1L, 60L);
	}

	@Test
	@DisplayName("창이 끝나 키가 사라지면 다시 쓸 수 있다")
	void rateLimitResetsAfterWindow() throws Exception {
		String token = signup(EMAIL, NICKNAME);
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(send(token, Map.of("questionId", 1))).andExpect(status().isOk());
		}
		mockMvc.perform(send(token, Map.of("questionId", 1))).andExpect(status().isTooManyRequests());

		// 1분을 기다리는 대신 창이 끝난 상태(키 만료)를 만든다.
		redisTemplate.expire(RATE_KEY_PREFIX + 1, Duration.ofMillis(1));
		Thread.sleep(20);

		mockMvc.perform(send(token, Map.of("questionId", 1))).andExpect(status().isOk());
		assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + 1)).isEqualTo("1");
	}

	// --- helpers ---

	private MockHttpServletRequestBuilder send(String accessToken, Map<String, ?> body) {
		return post(MESSAGES).header("Authorization", bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body));
	}

	private static String bearer(String accessToken) {
		return "Bearer " + accessToken;
	}

	private LlmRequest capturedRequest() {
		ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
		verify(llmClient).complete(captor.capture());
		return captor.getValue();
	}

	private List<Map<String, Object>> llmAuditRows() {
		return jdbcTemplate.queryForList("""
				SELECT actor_id, target_type, target_id, result, host(ip_address) AS ip, detail::text AS detail,
				       detail->>'outcome' AS outcome, detail->>'latencyMs' AS latency_ms,
				       detail->>'httpStatus' AS http_status
				FROM audit_logs WHERE action = 'EXTERNAL_LLM' ORDER BY audit_log_id""");
	}

	private Map<String, Object> singleLlmAuditRow() {
		List<Map<String, Object>> rows = llmAuditRows();
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private void withdraw(Long userId) {
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", userId);
	}

	private void suspend(Long userId) {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() + interval '1 day' "
				+ "WHERE user_id = ?", userId);
	}

	private String signup(String email, String nickname) throws Exception {
		MvcResult signup = mockMvc.perform(post("/api/v1/auth/email/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD,
								"nickname", nickname, "termsOfServiceAgreed", true, "privacyPolicyAgreed", true))))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(signup.getResponse().getContentAsString()).get("accessToken").asString();
	}

}
