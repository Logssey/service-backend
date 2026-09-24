package com.reused.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 필수 약관 2건은 각각 동의를 받는다. 개인정보보호법 제22조가 동의 사항을 구분해 받도록 요구하므로
 * 하나의 필드로 합치지 않는다(소셜 온보딩 명세).
 */
public record SignupRequest(
		@NotBlank String signupToken,
		@NotBlank @Size(min = 2, max = 20) String nickname,
		@AssertTrue boolean termsOfServiceAgreed,
		@AssertTrue boolean privacyPolicyAgreed) {
}
