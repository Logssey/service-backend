package com.reused.chatbot.llm;

/**
 * 공급자 중립 LLM 어댑터(ADR-003). 챗봇 서비스는 이 인터페이스만 알고 공급자 SDK에 의존하지 않는다.
 * 테스트에서는 {@code @MockitoBean}으로 바꾼다. 외부 LLM을 CI에서 호출하지 않는다.
 *
 * <p>구현은 요청에 사용자 식별 정보(userId, 닉네임, 이메일 등)를 담지 않는다(NFR-EXT-004).
 * 공급자 API에 사용자 식별 필드(metadata 등)가 있어도 채우지 않는다.
 */
public interface LlmClient {

	/**
	 * 호출에 필요한 설정(인증정보 등)이 있는가. false면 자유 입력은 503이고 추천 질문은 그대로 동작한다.
	 */
	boolean isConfigured();

	/**
	 * 단발 호출이다. 대화 이력을 보내지 않는다. 호출 스레드(챗봇 전용 풀)를 응답이 올 때까지 점유한다.
	 *
	 * @return 모델 답변 원문 또는 거절. 답변이 비어 있을 수 있으며 판정은 호출자가 한다
	 * @throws LlmCallException 호출 실패. 재시도는 구현이 제한된 횟수만 한다
	 */
	LlmReply complete(LlmRequest request);

}
