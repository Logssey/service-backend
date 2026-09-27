package com.reused.chatbot.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditResult;
import com.reused.audit.api.AuditTargetType;
import com.reused.chatbot.catalog.SuggestedQuestion;
import com.reused.chatbot.config.ChatbotExecutor;
import com.reused.chatbot.config.ChatbotProperties;
import com.reused.chatbot.dto.request.ChatbotMessageRequest;
import com.reused.chatbot.dto.response.ChatbotMessageResponse;
import com.reused.chatbot.dto.response.SuggestedQuestionResponse;
import com.reused.chatbot.llm.LlmCallException;
import com.reused.chatbot.llm.LlmClient;
import com.reused.chatbot.llm.LlmReply;
import com.reused.chatbot.llm.LlmRequest;
import com.reused.chatbot.prompt.ChatbotPrompt;
import com.reused.chatbot.prompt.PiiMasker;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.api.ActiveUserGuard;

/**
 * 챗봇 안내(FR-AI-001~007). 대화를 저장하지 않고 DB 데이터를 조회하거나 바꾸지 않는다(범위 밖: 챗봇을 통한 사용자 데이터
 * 조회·운영 행위). 그래서 트랜잭션이 없다.
 *
 * <p>메시지 전송 처리 순서. 앞 단계에서 끝난 요청은 호출 제한 횟수에 들어가지 않는다.
 * <ol>
 *   <li>회원 확인: 없거나 탈퇴 → 401. 정지 회원은 허용한다(이용정지 제한 표에 챗봇이 없다)
 *   <li>questionId·message 중 하나만, message는 공백 제외 1자 이상 → 아니면 400
 *   <li>기능 스위치 → 꺼져 있으면 503. 자유 입력은 LLM 설정이 없어도 503
 *   <li>호출 제한(추천 질문 포함) → 429
 *   <li>추천 질문은 사전 정의 답변(없는 id → 404), 자유 입력은 전용 풀에서 LLM 호출(포화 503, 실패·시간 초과 502)
 * </ol>
 *
 * <p>LLM 경로는 결과마다 {@code EXTERNAL_LLM} 감사 한 행을 별도 트랜잭션으로 남긴다(FR-LOG-006, NFR-EXT-005).
 * detail에는 결과 종류·지연·공급자 HTTP 상태만 두고 질문·답변 원문은 넣지 않는다.
 */
@Service
public class ChatbotService {

	private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);

	private static final String DISABLED_MESSAGE = "현재 챗봇을 이용할 수 없습니다.";
	private static final String LLM_FAILURE_MESSAGE = "챗봇 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";

	static final String OUTCOME_ANSWERED = "ANSWERED";
	static final String OUTCOME_OUT_OF_SCOPE = "OUT_OF_SCOPE";
	static final String OUTCOME_REFUSED = "REFUSED";
	static final String OUTCOME_EMPTY = "EMPTY";
	static final String OUTCOME_TIMEOUT = "TIMEOUT";
	static final String OUTCOME_REJECTED = "REJECTED";
	static final String OUTCOME_INTERRUPTED = "INTERRUPTED";
	static final String OUTCOME_ERROR = "ERROR";

	private static final String KEY_OUTCOME = "outcome";
	private static final String KEY_LATENCY_MS = "latencyMs";
	private static final String KEY_HTTP_STATUS = "httpStatus";

	private final ActiveUserGuard activeUserGuard;
	private final ChatbotRateLimiter rateLimiter;
	private final ChatbotExecutor executor;
	private final LlmClient llmClient;
	private final AuditLogger auditLogger;
	private final ChatbotProperties properties;

	public ChatbotService(ActiveUserGuard activeUserGuard, ChatbotRateLimiter rateLimiter, ChatbotExecutor executor,
			LlmClient llmClient, AuditLogger auditLogger, ChatbotProperties properties) {
		this.activeUserGuard = activeUserGuard;
		this.rateLimiter = rateLimiter;
		this.executor = executor;
		this.llmClient = llmClient;
		this.auditLogger = auditLogger;
		this.properties = properties;
	}

	/**
	 * 호출 제한 횟수에 들어가지 않는다(외부 호출도 사전 정의 답변도 없다).
	 */
	public List<SuggestedQuestionResponse> getSuggestedQuestions(Long userId) {
		activeUserGuard.requireMember(userId);
		requireEnabled();
		return SuggestedQuestion.all().stream().map(SuggestedQuestionResponse::from).toList();
	}

	public ChatbotMessageResponse sendMessage(Long userId, ChatbotMessageRequest request) {
		activeUserGuard.requireMember(userId);
		requireExactlyOne(request);

		if (request.questionId() != null) {
			requireEnabled();
			rateLimiter.acquire(userId);
			// 추천 질문은 LLM을 부르지 않는다(UC-09).
			return SuggestedQuestion.findById(request.questionId())
					.map(ChatbotMessageResponse::predefined)
					.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "추천 질문을 찾을 수 없습니다."));
		}

		String message = request.message().strip();
		if (message.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "질문을 입력해 주세요.");
		}
		requireEnabled();
		if (!properties.freeInputEnabled()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
					"현재 직접 입력한 질문에는 답할 수 없습니다. 추천 질문을 이용해 주세요.");
		}
		if (!llmClient.isConfigured()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "현재 직접 입력한 질문에는 답변할 수 없습니다. 추천 질문을 이용해 주세요.");
		}
		rateLimiter.acquire(userId);
		return askLlm(userId, message);
	}

	/** JSON 값이 null이 아니면 지정한 것으로 본다. {@code {"questionId":1,"message":""}}도 둘 다 지정이다. */
	private static void requireExactlyOne(ChatbotMessageRequest request) {
		if ((request.questionId() == null) == (request.message() == null)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "questionId와 message 중 하나만 지정해야 합니다.");
		}
	}

	private void requireEnabled() {
		if (!properties.enabled()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, DISABLED_MESSAGE);
		}
	}

	/**
	 * 요청 스레드는 전용 풀의 결과를 전체 제한 시간까지만 기다린다. 풀이 가득 차면 기다리지 않고 503이다(NFR-EXT-001).
	 * 시간이 지나면 작업을 끊고(인터럽트) 502다. 감사 기록은 요청 IP가 남도록 요청 스레드에서 한다.
	 */
	private ChatbotMessageResponse askLlm(Long userId, String message) {
		LlmRequest llmRequest = new LlmRequest(ChatbotPrompt.SYSTEM, PiiMasker.mask(message));
		long startedAt = System.nanoTime();

		Future<LlmReply> call;
		try {
			call = executor.submit(() -> llmClient.complete(llmRequest));
		}
		catch (RejectedExecutionException e) {
			log.warn("챗봇 전용 풀이 가득 차 LLM 호출을 거절했다.");
			recordCall(userId, AuditResult.FAILURE, OUTCOME_REJECTED, startedAt, null);
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "요청이 많아 잠시 후 다시 시도해 주세요.");
		}

		LlmReply reply;
		try {
			reply = call.get(properties.llm().timeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			call.cancel(true);
			log.warn("LLM 호출 제한 시간 초과. timeout={}", properties.llm().timeout());
			recordCall(userId, AuditResult.FAILURE, OUTCOME_TIMEOUT, startedAt, null);
			throw llmFailure();
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof LlmCallException failure) {
				recordCall(userId, AuditResult.FAILURE, failure.kind().name(), startedAt, failure.httpStatus());
			}
			else {
				// 어댑터가 분류하지 못한 예외. 메시지에 입력이 섞일 수 있어 타입만 남긴다.
				log.warn("LLM 어댑터 예외. exception={}", e.getCause() == null ? null : e.getCause().getClass().getName());
				recordCall(userId, AuditResult.FAILURE, OUTCOME_ERROR, startedAt, null);
			}
			throw llmFailure();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			call.cancel(true);
			recordCall(userId, AuditResult.FAILURE, OUTCOME_INTERRUPTED, startedAt, null);
			throw llmFailure();
		}
		return interpret(userId, reply, startedAt);
	}

	/**
	 * 범위 밖이면(모델 표식이나 공급자 거절) 모델 출력 대신 고정 문구를 보낸다. 빈 답변은 실패로 본다.
	 */
	private ChatbotMessageResponse interpret(Long userId, LlmReply reply, long startedAt) {
		if (reply.refused()) {
			recordCall(userId, AuditResult.SUCCESS, OUTCOME_REFUSED, startedAt, null);
			return ChatbotMessageResponse.outOfScope();
		}
		String answer = reply.text() == null ? "" : reply.text().strip();
		if (answer.isEmpty()) {
			recordCall(userId, AuditResult.FAILURE, OUTCOME_EMPTY, startedAt, null);
			throw llmFailure();
		}
		if (ChatbotPrompt.isOutOfScope(answer)) {
			recordCall(userId, AuditResult.SUCCESS, OUTCOME_OUT_OF_SCOPE, startedAt, null);
			return ChatbotMessageResponse.outOfScope();
		}
		recordCall(userId, AuditResult.SUCCESS, OUTCOME_ANSWERED, startedAt, null);
		return ChatbotMessageResponse.llm(truncate(answer, properties.llm().maxAnswerLength()));
	}

	/** 서로게이트 쌍(이모지 등)을 가르지 않도록 코드 포인트 단위로 자른다. */
	private static String truncate(String answer, int maxLength) {
		if (answer.codePointCount(0, answer.length()) <= maxLength) {
			return answer;
		}
		return answer.substring(0, answer.offsetByCodePoints(0, maxLength));
	}

	private void recordCall(Long userId, AuditResult result, String outcome, long startedAt, Integer httpStatus) {
		long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		log.debug("LLM 호출 결과. outcome={}, latencyMs={}", outcome, latencyMs);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put(KEY_OUTCOME, outcome);
		detail.put(KEY_LATENCY_MS, latencyMs);
		if (httpStatus != null) {
			detail.put(KEY_HTTP_STATUS, httpStatus);
		}
		auditLogger.recordSeparately(
				new AuditEntry(AuditAction.EXTERNAL_LLM, userId, AuditTargetType.EXTERNAL, null, result, detail));
	}

	private static BusinessException llmFailure() {
		return new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, LLM_FAILURE_MESSAGE);
	}

}
