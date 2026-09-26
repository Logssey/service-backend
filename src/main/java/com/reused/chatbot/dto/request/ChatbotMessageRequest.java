package com.reused.chatbot.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 챗봇 메시지 전송 요청. {@code questionId}와 {@code message} 중 하나만 지정한다(값이 null이 아니면 지정한 것).
 * 둘 다 있거나 둘 다 없는지는 서비스가 검사한다.
 *
 * @param questionId 추천 질문 선택 시. 추천 질문 목록의 questionId
 * @param message 자유 입력 시. 500자 이하(원문 기준)
 */
public record ChatbotMessageRequest(
		@Positive Long questionId,
		@Size(max = 500, message = "500자 이하로 입력해 주세요.") String message) {
}
