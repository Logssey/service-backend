package com.reused.chatbot.llm;

/**
 * LLM에 보내는 것은 이 두 값뿐이다(NFR-EXT-004).
 *
 * @param systemPrompt 정적 지시문. 사용자별 데이터를 넣지 않는다
 * @param userMessage 사용자가 입력한 문장(개인정보 마스킹 후). user 역할 메시지로만 전달하고 지시문에 이어 붙이지 않는다
 */
public record LlmRequest(String systemPrompt, String userMessage) {
}
