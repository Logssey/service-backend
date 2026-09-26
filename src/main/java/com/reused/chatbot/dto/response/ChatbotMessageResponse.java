package com.reused.chatbot.dto.response;

import com.reused.chatbot.catalog.SuggestedQuestion;

/**
 * @param answer 평문. 프론트는 텍스트로 렌더링한다(모델 출력은 신뢰하지 않는 입력, NFR-DATA-007)
 */
public record ChatbotMessageResponse(String answer, ChatbotAnswerSource source) {

	/** 범위 밖 질문은 모델 출력 대신 항상 이 문구를 돌려준다(챗봇 메시지 전송 명세 예시). */
	public static final String OUT_OF_SCOPE_ANSWER = "서비스 이용과 관련된 질문에만 답변할 수 있습니다.";

	public static ChatbotMessageResponse predefined(SuggestedQuestion question) {
		return new ChatbotMessageResponse(question.answer(), ChatbotAnswerSource.PREDEFINED);
	}

	public static ChatbotMessageResponse llm(String answer) {
		return new ChatbotMessageResponse(answer, ChatbotAnswerSource.LLM);
	}

	public static ChatbotMessageResponse outOfScope() {
		return new ChatbotMessageResponse(OUT_OF_SCOPE_ANSWER, ChatbotAnswerSource.OUT_OF_SCOPE);
	}

}
