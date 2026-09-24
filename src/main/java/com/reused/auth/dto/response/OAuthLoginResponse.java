package com.reused.auth.dto.response;

import com.reused.user.dto.response.UserSummaryResponse;
import com.reused.user.entity.User;

/**
 * 기존 회원이면 status=LOGIN + accessToken, 신규면 status=SIGNUP_REQUIRED + signupToken이다.
 * Refresh Token은 바디가 아니라 HttpOnly 쿠키로 나간다(소셜 로그인 명세).
 */
public record OAuthLoginResponse(
		LoginStatus status,
		String accessToken,
		String signupToken,
		UserSummaryResponse user) {

	public enum LoginStatus {
		LOGIN,
		SIGNUP_REQUIRED
	}

	public static OAuthLoginResponse login(String accessToken, User user) {
		return new OAuthLoginResponse(LoginStatus.LOGIN, accessToken, null, UserSummaryResponse.from(user));
	}

	public static OAuthLoginResponse signupRequired(String signupToken) {
		return new OAuthLoginResponse(LoginStatus.SIGNUP_REQUIRED, null, signupToken, null);
	}

}
