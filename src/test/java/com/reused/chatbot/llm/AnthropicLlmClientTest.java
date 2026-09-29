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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.reused.chatbot.config.ChatbotProperties;

/**
 * Anthropic 어댑터가 보내는 요청과 응답 해석. 로컬 HTTP 서버가 Messages API를 흉내 낸다. 실제 API는 부르지 않는다.
 */
class AnthropicLlmClientTest {

	private static final String API_KEY = "test-anthropic-key";
	private static final String SYSTEM_PROMPT = "안내 도우미 지시문";
	private static final String USER_MESSAGE = "거래는 어떻게 하나요?";

	private final ObjectMapper objectMapper = JsonMapper.builder().build();
	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
	private final List<AnthropicLlmClient> clients = new CopyOnWriteArrayList<>();

	private HttpServer server;
	private volatile Responder responder;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("anthropic-beta"),
					exchange.getRequestHeaders().getFirst("x-api-key"), body));
			responder.respond(exchange);
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		clients.forEach(AnthropicLlmClient::close);
		server.stop(0);
	}

	@Test
	@DisplayName("베타 메시지 경로로 폴백·adaptive 사고·LOW effort를 담아 보내고 사용자 식별 정보는 싣지 않는다")
	void requestShape() throws Exception {
		responder = json(200, message("end_turn", """
				[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":"판매자가 승인합니다."}]"""));

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.refused()).isFalse();
		assertThat(reply.text()).isEqualTo("판매자가 승인합니다.");

		assertThat(requests).hasSize(1);
		RecordedRequest request = requests.get(0);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/v1/messages");
		assertThat(request.query()).contains("beta=true");
		assertThat(request.beta()).contains(AnthropicLlmClient.FALLBACK_BETA);
		assertThat(request.apiKey()).isEqualTo(API_KEY);

		JsonNode body = objectMapper.readTree(request.body());
		assertThat(body.get("model").asString()).isEqualTo("claude-opus-5");
		assertThat(body.get("max_tokens").asLong()).isEqualTo(4096L);
		assertThat(body.get("system").asString()).isEqualTo(SYSTEM_PROMPT);
		assertThat(body.get("messages")).hasSize(1);
		assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
		assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo(USER_MESSAGE);
		assertThat(body.get("thinking").get("type").asString()).isEqualTo("adaptive");
		assertThat(body.get("output_config").get("effort").asString()).isEqualTo("low");
		assertThat(body.get("fallbacks").asString()).isEqualTo("default");
		assertThat(body.has("metadata")).as("사용자 식별 필드를 채우지 않는다").isFalse();
	}

	@Test
	@DisplayName("서버 측 폴백이 일어나면 폴백 표시 뒤의 텍스트만 답변으로 쓴다")
	void textAfterFallbackMarker() {
		responder = json(200, message("end_turn", """
				[{"type":"text","text":"거절 전 부분 출력"},
				 {"type":"fallback","from":{"model":"claude-opus-5"},"to":{"model":"claude-opus-4-8"}},
				 {"type":"text","text":"폴백 모델의 "},{"type":"text","text":"답변"}]"""));

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.text()).isEqualTo("폴백 모델의 답변");
	}

	@Test
	@DisplayName("stop_reason이 refusal이면 거절로 돌려준다")
	void refusal() {
		responder = json(200, """
				{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5","content":[],
				 "stop_reason":"refusal","stop_sequence":null,
				 "stop_details":{"type":"refusal","category":"cyber","explanation":"declined"},
				 "usage":{"input_tokens":10,"output_tokens":0}}""");

		LlmReply reply = client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		assertThat(reply.refused()).isTrue();
		assertThat(reply.text()).isNull();
	}

	@Test
	@DisplayName("공급자 429는 한 번만 재시도한 뒤 RATE_LIMITED다")
	void providerRateLimit() {
		responder = error(429, "rate_limit_error");

		assertFailure(LlmCallException.Kind.RATE_LIMITED, 429);
		assertThat(requests).hasSize(2);
	}

	@Test
	@DisplayName("공급자 5xx는 한 번만 재시도한 뒤 SERVER_ERROR다")
	void providerServerError() {
		responder = error(500, "api_error");

		assertFailure(LlmCallException.Kind.SERVER_ERROR, 500);
		assertThat(requests).hasSize(2);
	}

	@Test
	@DisplayName("인증정보 거부(401)와 요청 오류(400)는 재시도하지 않고 CLIENT_ERROR다")
	void providerClientErrors() {
		responder = error(401, "authentication_error");
		assertFailure(LlmCallException.Kind.CLIENT_ERROR, 401);
		assertThat(requests).hasSize(1);

		requests.clear();
		responder = error(400, "invalid_request_error");
		assertFailure(LlmCallException.Kind.CLIENT_ERROR, 400);
		assertThat(requests).hasSize(1);
	}

	/**
	 * SDK는 첫 호출에서 직렬화 대상 클래스를 리플렉션으로 한 번 훑는다(JVM 전역에 캐시). 그 시간이 제한 시간 측정에
	 * 섞이지 않도록 정상 응답으로 먼저 한 번 호출한다.
	 */
	@Test
	@DisplayName("응답이 오지 않으면 SDK 제한 시간(재시도 1회 포함) 안에 CONNECTION으로 끝난다")
	void unresponsiveProviderTimesOut() throws Exception {
		responder = json(200, message("end_turn", "[{\"type\":\"text\",\"text\":\"예열\"}]"));
		client(Duration.ofSeconds(5)).complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE));

		// accept하지 않아도 커널이 연결을 받아 두므로, 요청은 보내지지만 응답은 영영 오지 않는다.
		try (ServerSocket silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			AnthropicLlmClient client = track(new AnthropicLlmClient(properties(Map.of(
					"app.chatbot.llm.api-key", API_KEY,
					"app.chatbot.llm.base-url", "http://127.0.0.1:" + silentServer.getLocalPort(),
					"app.chatbot.llm.timeout", "300ms"))));

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
		AnthropicLlmClient client = new AnthropicLlmClient(properties(Map.of("app.chatbot.llm.api-key", "")));

		assertThat(client.isConfigured()).isFalse();
		assertThatThrownBy(() -> client.complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE)))
				.isInstanceOf(LlmCallException.class)
				.satisfies(e -> assertThat(((LlmCallException) e).kind())
						.isEqualTo(LlmCallException.Kind.NOT_CONFIGURED));
		assertThat(requests).isEmpty();
	}

	@Test
	@DisplayName("설정 기본값은 모델 claude-opus-5, 제한 시간 15초, 출력 4096토큰이고 toString은 키를 가린다")
	void propertyDefaults() {
		ChatbotProperties properties = properties(Map.of("app.chatbot.llm.api-key", API_KEY));

		assertThat(properties.enabled()).isTrue();
		assertThat(properties.rateLimit()).isEqualTo(5);
		assertThat(properties.rateWindow()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.executor().poolSize()).isEqualTo(4);
		assertThat(properties.executor().queueCapacity()).isEqualTo(8);
		assertThat(properties.llm().model()).isEqualTo("claude-opus-5");
		assertThat(properties.llm().timeout()).isEqualTo(Duration.ofSeconds(15));
		assertThat(properties.llm().maxTokens()).isEqualTo(4096L);
		assertThat(properties.llm().maxAnswerLength()).isEqualTo(1000);
		assertThat(properties.toString()).doesNotContain(API_KEY);
	}

	// --- helpers ---

	private void assertFailure(LlmCallException.Kind kind, int httpStatus) {
		AnthropicLlmClient client = client(Duration.ofSeconds(5));
		assertThatThrownBy(() -> client.complete(new LlmRequest(SYSTEM_PROMPT, USER_MESSAGE)))
				.isInstanceOf(LlmCallException.class)
				.satisfies(e -> {
					LlmCallException failure = (LlmCallException) e;
					assertThat(failure.kind()).isEqualTo(kind);
					assertThat(failure.httpStatus()).isEqualTo(httpStatus);
					assertThat(failure.getMessage()).doesNotContain(API_KEY, USER_MESSAGE);
				});
	}

	private AnthropicLlmClient client(Duration timeout) {
		return track(new AnthropicLlmClient(properties(Map.of(
				"app.chatbot.llm.api-key", API_KEY,
				"app.chatbot.llm.base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
				"app.chatbot.llm.timeout", timeout.toMillis() + "ms"))));
	}

	private AnthropicLlmClient track(AnthropicLlmClient client) {
		clients.add(client);
		return client;
	}

	private static ChatbotProperties properties(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
				.bindOrCreate("app.chatbot", ChatbotProperties.class);
	}

	private static String message(String stopReason, String content) {
		return """
				{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5","content":%s,
				 "stop_reason":"%s","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
				.formatted(content, stopReason);
	}

	private static Responder json(int status, String body) {
		return exchange -> write(exchange, status, body);
	}

	/** 재시도 대기를 줄이려고 retry-after-ms를 준다. */
	private static Responder error(int status, String type) {
		return exchange -> {
			exchange.getResponseHeaders().add("retry-after-ms", "10");
			write(exchange, status, """
					{"type":"error","error":{"type":"%s","message":"provider says no"}}""".formatted(type));
		};
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

	private record RecordedRequest(String method, String path, String query, String beta, String apiKey, String body) {
	}

}
