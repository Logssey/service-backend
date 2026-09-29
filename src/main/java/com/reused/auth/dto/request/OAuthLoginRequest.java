package com.reused.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record OAuthLoginRequest(
		@NotBlank String code,
		@NotBlank String redirectUri) {
}
