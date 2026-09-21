package com.reused.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank String signupToken,
		@NotBlank @Size(min = 2, max = 20) String nickname,
		@AssertTrue boolean termsAgreed) {
}
