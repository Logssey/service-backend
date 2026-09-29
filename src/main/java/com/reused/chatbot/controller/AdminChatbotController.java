package com.reused.chatbot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.chatbot.dto.request.ChatbotFeatureUpdateRequest;
import com.reused.chatbot.dto.response.ChatbotFeatureStatusResponse;
import com.reused.chatbot.service.ChatbotFeatureService;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;

import jakarta.validation.Valid;

/** SecurityConfig and AdminAccessInterceptor enforce current ADMIN status. */
@RestController
@RequestMapping("/api/v1/admin/chatbot")
public class AdminChatbotController {

	private final ChatbotFeatureService featureService;

	public AdminChatbotController(ChatbotFeatureService featureService) {
		this.featureService = featureService;
	}

	@GetMapping
	public ChatbotFeatureStatusResponse status() {
		return featureService.status();
	}

	@PatchMapping
	public ChatbotFeatureStatusResponse update(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody ChatbotFeatureUpdateRequest request) {
		return featureService.update(principal.userId(), request.enabled());
	}
}
