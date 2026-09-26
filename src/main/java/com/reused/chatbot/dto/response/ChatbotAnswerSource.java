package com.reused.chatbot.dto.response;

/**
 * 답변 출처(챗봇 메시지 전송 명세).
 */
public enum ChatbotAnswerSource {
	/** 추천 질문의 사전 정의 답변 */
	PREDEFINED,
	/** 외부 LLM이 생성한 답변 */
	LLM,
	/** 서비스 범위 밖 질문. 답변은 고정 안내 문구다 */
	OUT_OF_SCOPE
}
