package com.reused.chatbot.dto.response;

import com.reused.chatbot.catalog.SuggestedQuestion;

/**
 * @param questionId 메시지 전송의 questionId로 그대로 보낸다
 */
public record SuggestedQuestionResponse(Long questionId, String question) {

	public static SuggestedQuestionResponse from(SuggestedQuestion question) {
		return new SuggestedQuestionResponse(question.id(), question.question());
	}

}
