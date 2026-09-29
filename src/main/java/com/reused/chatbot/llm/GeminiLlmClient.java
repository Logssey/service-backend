package com.reused.chatbot.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.reused.chatbot.config.ChatbotProperties;

/**
 * Gemini API {@code generateContent} 어댑터. 공급자 SDK 대신 REST로 부른다.
 *
 * <p>공급자 안전 정책이 입력을 막거나({@code promptFeedback.blockReason}) 답변을 중단하면
 * ({@code finishReason}이 {@link #REFUSAL_FINISH_REASONS} 중 하나) {@link LlmReply#refusal()}이다.
 * 사고 파트({@code thought=true})는 답변에서 뺀다.
 *
 * <p>연결·읽기 제한 시간은 시도 하나에 걸고, 429·5xx·연결 실패만 {@value #MAX_RETRIES}회 재시도한다
 * (ADR-003 "재시도 제한", {@link AnthropicLlmClient}와 같은 정책). 요청 전체 제한 시간은 서비스가 전용 풀의
 * {@code Future}로 따로 강제한다.
 *
 * <p>API 키가 비어 있으면 기동은 되고 {@link #isConfigured()}가 false다. 키는 URL이 아니라
 * {@code x-goog-api-key} 헤더로 보낸다. 쿼리 문자열에 두면 접근 로그에 남을 수 있다.
 *
 * <p>{@code app.chatbot.llm.provider=gemini}(기본값)일 때 뜬다.
 */
@Component
@ConditionalOnProperty(prefix = "app.chatbot.llm", name = "provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

	static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
	static final String API_KEY_HEADER = "x-goog-api-key";
	private static final int MAX_RETRIES = 1;

	/** 안전·저작권 정책으로 답변을 끝낸 경우. MAX_TOKENS는 잘린 답변이라 거절이 아니다. */
	static final Set<String> REFUSAL_FINISH_REASONS = Set.of(
			"SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY");

	private final ChatbotProperties.Llm properties;
	private final ChatbotProperties.Gemini gemini;
	private final RestClient restClient;
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	public GeminiLlmClient(RestClient.Builder builder, ChatbotProperties properties) {
		this.properties = properties.llm();
		this.gemini = properties.llm().gemini();
		HttpClientSettings settings = HttpClientSettings.defaults()
				.withTimeouts(this.properties.timeout(), this.properties.timeout());
		// detect()는 클래스패스에 Apache HttpClient가 있으면 그것을 고르는데, Apache는 429를 스스로 한 번 더 재시도한다.
		// 재시도 횟수를 이 클래스 한 곳에서 정하려고 재시도가 없는 JDK 클라이언트를 쓴다.
		this.restClient = builder
				.baseUrl(StringUtils.hasText(gemini.baseUrl()) ? gemini.baseUrl() : DEFAULT_BASE_URL)
				.requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
				.build();
		if (!isConfigured()) {
			log.info("LLM API 키가 없어 챗봇 자유 입력 답변이 꺼져 있다. 추천 질문 답변만 동작한다.");
		}
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(gemini.apiKey());
	}

	@Override
	public LlmReply complete(LlmRequest request) {
		if (!isConfigured()) {
			throw new LlmCallException(LlmCallException.Kind.NOT_CONFIGURED, null, null);
		}
		String body = requestBody(request);
		for (int attempt = 0; ; attempt++) {
			try {
				return send(body);
			}
			catch (LlmCallException e) {
				if (attempt >= MAX_RETRIES || !retryable(e.kind())) {
					throw failure(e);
				}
			}
		}
	}

	/** 사용자 식별 정보는 싣지 않는다(NFR-EXT-004). Gemini에는 사용자 식별 필드가 없어 빼먹을 일도 없다. */
	private String requestBody(LlmRequest request) {
		ObjectNode root = objectMapper.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
		ObjectNode user = root.putArray("contents").addObject();
		user.put("role", "user");
		user.putArray("parts").addObject().put("text", request.userMessage());
		root.putObject("generationConfig").put("maxOutputTokens", properties.maxTokens());
		return objectMapper.writeValueAsString(root);
	}

	private LlmReply send(String body) {
		String responseBody;
		int status;
		try {
			Response response = restClient.post()
					.uri("/v1beta/models/{model}:generateContent", gemini.model())
					.header(API_KEY_HEADER, gemini.apiKey())
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.exchange((req, res) -> new Response(res.getStatusCode().value(), read(res.getBody())));
			status = response.status();
			responseBody = response.body();
		}
		catch (ResourceAccessException e) {
			throw new LlmCallException(LlmCallException.Kind.CONNECTION, null, e);
		}
		if (status == 429) {
			throw new LlmCallException(LlmCallException.Kind.RATE_LIMITED, status, null);
		}
		if (status >= 500) {
			throw new LlmCallException(LlmCallException.Kind.SERVER_ERROR, status, null);
		}
		if (status >= 400) {
			// Gemini는 틀린 키를 401이 아니라 400(API_KEY_INVALID)으로 돌려준다. 키 값과 응답 본문은 찍지 않는다.
			if (status == 401 || status == 403 || responseBody.contains("API_KEY_INVALID")) {
				log.error("LLM 인증정보가 거부됐다. status={}", status);
			}
			throw new LlmCallException(LlmCallException.Kind.CLIENT_ERROR, status, null);
		}
		return toReply(responseBody);
	}

	private LlmReply toReply(String responseBody) {
		JsonNode root;
		try {
			root = objectMapper.readTree(responseBody);
		}
		catch (JacksonException e) {
			throw new LlmCallException(LlmCallException.Kind.INVALID_RESPONSE, null, e);
		}
		if (StringUtils.hasText(root.path("promptFeedback").path("blockReason").asString(null))) {
			return LlmReply.refusal();
		}
		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			throw new LlmCallException(LlmCallException.Kind.INVALID_RESPONSE, null, null);
		}
		if (REFUSAL_FINISH_REASONS.contains(candidate.path("finishReason").asString(""))) {
			return LlmReply.refusal();
		}
		StringBuilder answer = new StringBuilder();
		JsonNode parts = candidate.path("content").path("parts");
		if (parts instanceof ArrayNode array) {
			for (JsonNode part : array) {
				if (!part.path("thought").asBoolean(false)) {
					answer.append(part.path("text").asString(""));
				}
			}
		}
		return LlmReply.answered(answer.toString());
	}

	private static boolean retryable(LlmCallException.Kind kind) {
		return kind == LlmCallException.Kind.RATE_LIMITED || kind == LlmCallException.Kind.SERVER_ERROR
				|| kind == LlmCallException.Kind.CONNECTION;
	}

	/** 공급자 응답 본문에는 입력 일부가 되풀이될 수 있어 로그에 싣지 않는다. 종류와 상태만 남긴다. */
	private static LlmCallException failure(LlmCallException e) {
		log.warn("LLM 호출 실패. kind={}, status={}, exception={}", e.kind(), e.httpStatus(),
				e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
		return e;
	}

	private static String read(InputStream body) throws IOException {
		return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
	}

	private record Response(int status, String body) {
	}

}
