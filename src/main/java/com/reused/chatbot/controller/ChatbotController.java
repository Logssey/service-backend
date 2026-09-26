package com.reused.chatbot.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.chatbot.dto.request.ChatbotMessageRequest;
import com.reused.chatbot.dto.response.ChatbotMessageResponse;
import com.reused.chatbot.dto.response.SuggestedQuestionResponse;
import com.reused.chatbot.service.ChatbotService;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;

import jakarta.validation.Valid;

/**
 * 챗봇 엔드포인트(05-api/endpoints/chatbot).
 */
@RestController
@RequestMapping("/api/v1/chatbot")
public class ChatbotController {

	private final ChatbotService chatbotService;

	public ChatbotController(ChatbotService chatbotService) {
		this.chatbotService = chatbotService;
	}

	/** GET /chatbot/suggested-questions — USER. 페이지 없이 배열 그대로 */
	@GetMapping("/suggested-questions")
	public List<SuggestedQuestionResponse> suggestedQuestions(@AuthUser AuthPrincipal principal) {
		return chatbotService.getSuggestedQuestions(principal.userId());
	}

	/** POST /chatbot/messages — USER */
	@PostMapping("/messages")
	public ChatbotMessageResponse sendMessage(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody ChatbotMessageRequest request) {
		return chatbotService.sendMessage(principal.userId(), request);
	}

}
