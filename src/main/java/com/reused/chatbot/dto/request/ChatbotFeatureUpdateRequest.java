package com.reused.chatbot.dto.request;

import jakarta.validation.constraints.NotNull;

public record ChatbotFeatureUpdateRequest(@NotNull Boolean enabled) {
}
