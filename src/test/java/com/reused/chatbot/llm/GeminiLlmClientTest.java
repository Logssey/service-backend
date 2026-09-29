package com.reused.chatbot.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.reused.chatbot.config.ChatbotProperties;

/**
 * Gemini 어댑터가 보내는 요청과 응답 해석. 로컬 HTTP 서버가 generateContent를 흉내 낸다. 실제 API는 부르지 않는다.
 */
class GeminiLlmClientTest {

	private static final String API_KEY = "test-gemini-key";
	private static final String SYSTEM_PROMPT = "안내 도우미 지시문";
	private static final String USER_MESSAGE = "거래는 어떻게 하나요?";

	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

	private HttpServer server;
	private volatile Responder responder;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestURI().getQuery(),
					exchange.getRequestHeaders().getFirst(GeminiLlmClient.API_KEY_HEADER), body));
			responder.respond(exchange);
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	@DisplayName("generateContent 경로에 키를 헤더로 싣고, 지시문과 사용자 문장만 보낸다")
	void requestShape() throws Exception {
		responder = json(200, candidate("STOP", """
				[{"text":"판매자가 ","thought":false},{"text":"승인합니다."}]"""));

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.refused()).isFalse();
		assertThat(reply.text()).isEqualTo("판매자가 승인합니다.");

		assertThat(requests).hasSize(1);
		RecordedRequest request = requests.get(0);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3.5-flash-lite:generateContent");
		assertThat(request.query()).as("키를 URL에 싣지 않는다").isNull();
		assertThat(request.apiKey()).isEqualTo(API_KEY);

		JsonNode body = objectMapper.readTree(request.body());
		assertThat(body.get("systemInstruction").get("parts").get(0).get("text").asString()).isEqualTo(SYSTEM_PROMPT);
		assertThat(body.get("contents")).hasSize(1);
		assertThat(body.get("contents").get(0).get("role").asString()).isEqualTo("user");
		assertThat(body.get("contents").get(0).get("parts").get(0).get("text").asString()).isEqualTo(USER_MESSAGE);
		assertThat(body.get("generationConfig").get("maxOutputTokens").asLong()).isEqualTo(4096L);
		assertThat(body.propertyNames()).as("사용자 식별 필드를 싣지 않는다")
				.containsExactlyInAnyOrder("systemInstruction", "contents", "generationConfig");
	}

	@Test
	@DisplayName("사고 파트는 답변에서 뺀다")
	void thoughtPartsAreDropped() {
		responder = json(200, candidate("STOP", """
				[{"text":"먼저 거래 규칙을 떠올린다","thought":true},{"text":"직거래를 권합니다."}]"""));

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.text()).isEqualTo("직거래를 권합니다.");
	}

	@Test
	@DisplayName("출력 상한에서 끊긴 답변은 거절이 아니라 잘린 답변이다")
	void maxTokensIsPartialAnswer() {
		responder = json(200, candidate("MAX_TOKENS", "[{\"text\":\"중간까지 쓴 답\"}]"));

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.refused()).isFalse();
		assertThat(reply.text()).isEqualTo("중간까지 쓴 답");
	}

	@Test
	@DisplayName("입력이 안전 정책에 막히면(promptFeedback.blockReason) 거절이다")
	void blockedPromptIsRefusal() {
		responder = json(200, """
				{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}""");

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.refused()).isTrue();
		assertThat(reply.text()).isNull();
	}

	@Test
	@DisplayName("답변이 안전 정책으로 끝나면(finishReason SAFETY) 거절이다")
	void safetyFinishIsRefusal() {
		responder = json(200, candidate("SAFETY", "[]"));

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.refused()).isTrue();
	}

	@Test
	@DisplayName("후보가 없거나 JSON이 아니면 INVALID_RESPONSE다")
	void unreadableResponse() {
		responder = json(200, "{\"candidates\":[]}");
		assertFailure(LlmCallException.Kind.INVALID_RESPONSE, null);

		responder = json(200, "not json");
		assertFailure(LlmCallException.Kind.INVALID_RESPONSE, null);
	}

	@Test
	@DisplayName("공급자 429는 한 번만 재시도한 뒤 RATE_LIMITED다")
	void providerRateLimit() {
		responder = error(429, "RESOURCE_EXHAUSTED");

		assertFailure(LlmCallException.Kind.RATE_LIMITED, 429);
		assertThat(requests).hasSize(2);
	}

	@Test
	@DisplayName("공급자 5xx는 한 번만 재시도한 뒤 SERVER_ERROR다")
	void providerServerError() {
		responder = error(500, "INTERNAL");

		assertFailure(LlmCallException.Kind.SERVER_ERROR, 500);
		assertThat(requests).hasSize(2);
	}

	@Test
	@DisplayName("틀린 키(400 API_KEY_INVALID)와 권한 없음(403)은 재시도하지 않고 CLIENT_ERROR다")
	void providerClientErrors() {
		responder = error(400, "INVALID_ARGUMENT", "API_KEY_INVALID");
		assertFailure(LlmCallException.Kind.CLIENT_ERROR, 400);
		assertThat(requests).hasSize(1);

		requests.clear();
		responder = error(403, "PERMISSION_DENIED");
		assertFailure(LlmCallException.Kind.CLIENT_ERROR, 403);
		assertThat(requests).hasSize(1);
	}

	@Test
	@DisplayName("응답이 오지 않으면 제한 시간(재시도 1회 포함) 안에 CONNECTION으로 끝난다")
	void unresponsiveProviderTimesOut() throws Exception {
		// accept하지 않아도 커널이 연결을 받아 두므로, 요청은 보내지지만 응답은 영영 오지 않는다.
		try (ServerSocket silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			GeminiLlmClient client = new GeminiLlmClient(RestClient.builder(), properties(Map.of(
					"app.chatbot.llm.gemini.api-key", API_KEY,
					"app.chatbot.llm.gemini.base-url", "http://127.0.0.1:" + silentServer.getLocalPort(),
					"app.chatbot.llm.timeout", "300ms")));

			long startedAt = System.nanoTime();
			assertThatThrownBy(() -> client.complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE)))
					.isInstanceOf(LlmCallException.class)
					.satisfies(e -> assertThat(((LlmCallException) e).kind())
							.isEqualTo(LlmCallException.Kind.CONNECTION));
			assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
		}
	}

	@Test
	@DisplayName("API 키가 비어 있으면 설정되지 않은 상태이고 호출하면 NOT_CONFIGURED다")
	void blankApiKeyIsNotConfigured() {
		GeminiLlmClient client = new GeminiLlmClient(RestClient.builder(),
				properties(Map.of("app.chatbot.llm.gemini.api-key", "")));

		assertThat(client.isConfigured()).isFalse();
		assertThatThrownBy(() -> client.complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE)))
				.isInstanceOf(LlmCallException.class)
				.satisfies(e -> assertThat(((LlmCallException) e).kind())
						.isEqualTo(LlmCallException.Kind.NOT_CONFIGURED));
		assertThat(requests).isEmpty();
	}

	@Test
	@DisplayName("기본 공급자는 Gemini, 모델은 gemini-3.5-flash-lite이고 toString은 키를 가린다")
	void propertyDefaults() {
		ChatbotProperties properties = properties(Map.of("app.chatbot.llm.gemini.api-key", API_KEY));

		assertThat(properties.llm().provider()).isEqualTo(ChatbotProperties.Llm.Provider.GEMINI);
		assertThat(properties.llm().gemini().model()).isEqualTo("gemini-3.5-flash-lite");
		assertThat(properties.toString()).doesNotContain(API_KEY);
	}

	@Test
	@DisplayName("provider 설정에 따라 한 공급자의 어댑터만 뜬다")
	void providerSelectsOneAdapter() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AdapterConfig.class);

		runner.run(context -> assertThat(context).hasSingleBean(LlmClient.class)
				.hasSingleBean(GeminiLlmClient.class).doesNotHaveBean(AnthropicLlmClient.class));
		runner.withPropertyValues("app.chatbot.llm.provider=gemini")
				.run(context -> assertThat(context).hasSingleBean(GeminiLlmClient.class));
		runner.withPropertyValues("app.chatbot.llm.provider=anthropic")
				.run(context -> assertThat(context).hasSingleBean(LlmClient.class)
						.hasSingleBean(AnthropicLlmClient.class).doesNotHaveBean(GeminiLlmClient.class));
	}

	// --- helpers ---

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ChatbotProperties.class)
	@Import({ GeminiLlmClient.class, AnthropicLlmClient.class })
	static class AdapterConfig {

		@Bean
		RestClient.Builder restClientBuilder() {
			return RestClient.builder();
		}

	}

	private void assertFailure(LlmCallException.Kind kind, Integer httpStatus) {
		GeminiLlmClient client = client(Duration.ofSeconds(5));
		assertThatThrownBy(() -> client.complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE)))
				.isInstanceOf(LlmCallException.class)
				.satisfies(e -> {
					LlmCallException failure = (LlmCallException) e;
					assertThat(failure.kind()).isEqualTo(kind);
					assertThat(failure.httpStatus()).isEqualTo(httpStatus);
					assertThat(failure.getMessage()).doesNotContain(API_KEY, USER_MESSAGE);
				});
	}

	private GeminiLlmClient client(Duration timeout) {
		return new GeminiLlmClient(RestClient.builder(), properties(Map.of(
				"app.chatbot.llm.gemini.api-key", API_KEY,
				"app.chatbot.llm.gemini.base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
				"app.chatbot.llm.timeout", timeout.toMillis() + "ms")));
	}

	private static ChatbotProperties properties(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
				.bindOrCreate("app.chatbot", ChatbotProperties.class);
	}

	private static String candidate(String finishReason, String parts) {
		return """
				{"candidates":[{"content":{"role":"model","parts":%s},"finishReason":"%s","index":0}],
				 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
				.formatted(parts, finishReason);
	}

	private static Responder json(int status, String body) {
		return exchange -> write(exchange, status, body);
	}

	private static Responder error(int status, String code, String... reasons) {
		String details = reasons.length == 0 ? "" : ",\"details\":[{\"reason\":\"" + reasons[0] + "\"}]";
		return exchange -> write(exchange, status, """
				{"error":{"code":%d,"message":"provider says no","status":"%s"%s}}"""
				.formatted(status, code, details));
	}

	private static void write(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@FunctionalInterface
	private interface Responder {
		void respond(HttpExchange exchange) throws IOException;
	}

	private record RecordedRequest(String method, String path, String query, String apiKey, String body) {
	}

}
