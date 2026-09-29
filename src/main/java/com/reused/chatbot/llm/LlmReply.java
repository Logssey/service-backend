package com.reused.chatbot.llm;

/**
 * LLM 호출 결과. 호출은 성공했지만 모델(또는 공급자 안전 정책)이 답변을 거절했으면 {@code refused}다.
 *
 * @param text 거절이면 null
 */
public record LlmReply(boolean refused, String text) {

	public static LlmReply answered(String text) {
		return new LlmReply(false, text);
	}

	public static LlmReply refusal() {
		return new LlmReply(true, null);
	}

}
