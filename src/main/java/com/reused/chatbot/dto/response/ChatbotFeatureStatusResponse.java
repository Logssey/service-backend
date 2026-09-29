package com.reused.chatbot.dto.response;

/** Effective state may be off even when the administrator switch is on. */
public record ChatbotFeatureStatusResponse(boolean enabled, boolean adminEnabled,
		boolean environmentEnabled, boolean freeInputEnabled) {
}
