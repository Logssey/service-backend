package com.reused.chatbot.llm;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonObject;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.reused.chatbot.config.ChatbotProperties;

/**
 * Anthropic Messages API 어댑터. 짧은 상담 답변이라 사고는 adaptive, effort는 LOW로 둔다.
 *
 * <p>공급자 안전 정책이 요청을 거절하면 서버 측 폴백(beta {@value #FALLBACK_BETA}, {@code fallbacks: "default"})이
 * 다른 모델로 한 번 더 시도한다. 그래도 거절이면({@code stop_reason=refusal}) {@link LlmReply#refusal()}이다.
 * 폴백은 정책 거절에만 동작하고 호출량 초과·서버 오류에는 동작하지 않는다.
 *
 * <p>SDK 제한 시간은 요청 하나(시도 하나)에, 재시도는 {@value #MAX_RETRIES}회로 건다(ADR-003 "재시도 제한").
 * 요청 전체 제한 시간은 서비스가 전용 풀의 {@code Future}로 따로 강제한다.
 *
 * <p>API 키가 비어 있으면 클라이언트를 만들지 않는다. 기동은 되고 {@link #isConfigured()}가 false다.
 */
@Component
public class AnthropicLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

	static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
	private static final String FALLBACKS_PROPERTY = "fallbacks";
	private static final String FALLBACKS_DEFAULT = "default";
	private static final int MAX_RETRIES = 1;

	/** 폴백이 일어난 지점을 표시하는 content 블록. SDK가 아직 모르는 타입이라 원시 JSON으로 확인한다. */
	private static final JsonString FALLBACK_BLOCK_TYPE = JsonString.of("fallback");

	private final ChatbotProperties.Llm properties;
	private final AnthropicClient client;

	public AnthropicLlmClient(ChatbotProperties properties) {
		this.properties = properties.llm();
		this.client = StringUtils.hasText(this.properties.apiKey()) ? createClient(this.properties) : null;
		if (this.client == null) {
			log.info("LLM API 키가 없어 챗봇 자유 입력 답변이 꺼져 있다. 추천 질문 답변만 동작한다.");
		}
	}

	private static AnthropicClient createClient(ChatbotProperties.Llm properties) {
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
				.apiKey(properties.apiKey())
				.timeout(properties.timeout())
				.maxRetries(MAX_RETRIES);
		if (StringUtils.hasText(properties.baseUrl())) {
			builder.baseUrl(properties.baseUrl());
		}
		return builder.build();
	}

	@Override
	public boolean isConfigured() {
		return client != null;
	}

	@Override
	public LlmReply complete(LlmRequest request) {
		if (client == null) {
			throw new LlmCallException(LlmCallException.Kind.NOT_CONFIGURED, null, null);
		}
		// metadata(user_id)는 채우지 않는다(NFR-EXT-004).
		MessageCreateParams params = MessageCreateParams.builder()
				.model(properties.model())
				.maxTokens(properties.maxTokens())
				.system(request.systemPrompt())
				.addUserMessage(request.userMessage())
				.thinking(BetaThinkingConfigAdaptive.builder().build())
				.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
				.addBeta(FALLBACK_BETA)
				.putAdditionalBodyProperty(FALLBACKS_PROPERTY, JsonValue.from(FALLBACKS_DEFAULT))
				.build();
		try {
			return toReply(client.beta().messages().create(params));
		}
		catch (RateLimitException e) {
			throw failure(LlmCallException.Kind.RATE_LIMITED, e.statusCode(), e);
		}
		catch (InternalServerException e) {
			throw failure(LlmCallException.Kind.SERVER_ERROR, e.statusCode(), e);
		}
		catch (AnthropicServiceException e) {
			if (e.statusCode() == 401 || e.statusCode() == 403) {
				// 키가 틀렸거나 폐기됐다. 운영자가 바로 알아야 하므로 ERROR로 남긴다. 키 값은 찍지 않는다.
				log.error("LLM 인증정보가 거부됐다. status={}", e.statusCode());
			}
			throw failure(LlmCallException.Kind.CLIENT_ERROR, e.statusCode(), e);
		}
		catch (AnthropicIoException e) {
			throw failure(LlmCallException.Kind.CONNECTION, null, e);
		}
		catch (AnthropicException e) {
			throw failure(LlmCallException.Kind.INVALID_RESPONSE, null, e);
		}
	}

	/**
	 * 폴백이 일어나면 content에 거절된 모델의 부분 출력, 폴백 표시 블록, 폴백 모델의 출력이 차례로 온다.
	 * 마지막 폴백 표시 뒤의 텍스트만 답변으로 쓴다. 사고 블록은 버린다.
	 */
	private static LlmReply toReply(BetaMessage message) {
		if (message.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
			return LlmReply.refusal();
		}
		StringBuilder answer = new StringBuilder();
		for (BetaContentBlock block : message.content()) {
			if (isFallbackMarker(block)) {
				answer.setLength(0);
				continue;
			}
			block.text().ifPresent(text -> answer.append(text.text()));
		}
		return LlmReply.answered(answer.toString());
	}

	private static boolean isFallbackMarker(BetaContentBlock block) {
		return block._json()
				.filter(JsonObject.class::isInstance)
				.map(json -> FALLBACK_BLOCK_TYPE.equals(((JsonObject) json).values().get("type")))
				.orElse(false);
	}

	/** 공급자 응답 본문에는 입력 일부가 되풀이될 수 있어 로그에 싣지 않는다. 종류와 상태만 남긴다. */
	private static LlmCallException failure(LlmCallException.Kind kind, Integer httpStatus, Exception e) {
		log.warn("LLM 호출 실패. kind={}, status={}, exception={}", kind, httpStatus, e.getClass().getSimpleName());
		return new LlmCallException(kind, httpStatus, e);
	}

	@PreDestroy
	void close() {
		if (client != null) {
			client.close();
		}
	}

}
