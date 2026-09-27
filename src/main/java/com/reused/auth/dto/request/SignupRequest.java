package com.reused.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 필수 약관 2건은 각각 동의를 받는다. 개인정보보호법 제22조가 동의 사항을 구분해 받도록 요구하므로
 * 하나의 필드로 합치지 않는다(소셜 온보딩 명세).
 *
 * <p>이메일은 선택이다(ADR-016). 생략·null·빈 문자열은 입력하지 않은 것으로 보고, 공백만 있거나 형식이 틀리면 400이다.
 * 이메일을 입력하면 선택 동의 {@code emailCollectionAgreed}가 true여야 한다. 필수 약관과 섞지 않도록 별도 필드로 받고,
 * 이메일이 없으면 이 값은 무시한다. 판정은 정규화 뒤의 이메일로 하므로 서비스({@code AuthService#signup})에서 한다.
 */
public record SignupRequest(
		@NotBlank String signupToken,
		@NotBlank @Size(min = 2, max = 20) String nickname,
		@Email @Size(max = 254) String email,
		Boolean emailCollectionAgreed,
		@AssertTrue boolean termsOfServiceAgreed,
		@AssertTrue boolean privacyPolicyAgreed) {
}
