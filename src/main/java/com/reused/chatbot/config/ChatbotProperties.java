package com.reused.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 챗봇 설정(ADR-003). 호출 제한 기본값은 business-rules 4장 "챗봇 호출 제한 사용자당 분당 5회"와
 * redis-keys.md의 TTL 1분이다. 타임아웃·풀 크기는 문서에 수치가 없어 0단계 계약이 정했다.
 *
 * @param enabled 기능 비활성화 스위치(ADR-003). false면 두 엔드포인트 모두 503이다. 바꾸려면 재기동한다
 * @param rateLimit 창 하나에서 허용하는 호출 수. 넘으면 429
 * @param rateWindow 호출 제한 창(고정 창)
 */
@ConfigurationProperties(prefix = "app.chatbot")
public record ChatbotProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("false") boolean freeInputEnabled,
		@DefaultValue("5") int rateLimit,
		@DefaultValue("1m") Duration rateWindow,
		@DefaultValue ExecutorSettings executor,
		@DefaultValue Llm llm) {

	/**
	 * 챗봇 전용 스레드 풀(ADR-003). LLM을 기다리는 요청은 최대 poolSize + queueCapacity개이고, 나머지는 즉시 503이다.
	 */
	public record ExecutorSettings(
			@DefaultValue("4") int poolSize,
			@DefaultValue("8") int queueCapacity) {
	}

	/**
	 * @param apiKey 비어 있으면 LLM이 꺼진다. 추천 질문은 동작하고 자유 입력만 503이다. 환경변수로만 주입한다(NFR-CRED-007)
	 * @param baseUrl 비어 있으면 SDK 기본 주소. 게이트웨이를 거칠 때만 쓴다
	 * @param timeout 요청 하나의 전체 제한 시간(NFR-EXT-002). 풀 대기, 재시도, 응답을 모두 포함한다
	 * @param maxTokens 모델 출력 토큰 상한(사고 포함)
	 * @param maxAnswerLength 응답에 싣는 답변 최대 글자 수. 넘으면 자른다
	 */
	public record Llm(
			String apiKey,
			String baseUrl,
			@DefaultValue("claude-opus-5") String model,
			@DefaultValue("15s") Duration timeout,
			@DefaultValue("4096") long maxTokens,
			@DefaultValue("1000") int maxAnswerLength) {

		/** 인증정보를 로그·예외 메시지에 평문으로 남기지 않는다(NFR-CRED-006). */
		@Override
		public String toString() {
			return "Llm[apiKey=" + (apiKey == null || apiKey.isEmpty() ? "" : "****") + ", baseUrl=" + baseUrl
					+ ", model=" + model + ", timeout=" + timeout + ", maxTokens=" + maxTokens
					+ ", maxAnswerLength=" + maxAnswerLength + "]";
		}

	}

}
