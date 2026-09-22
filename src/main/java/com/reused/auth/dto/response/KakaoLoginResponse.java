package com.reused.auth.dto.response;

import com.reused.user.dto.response.UserSummaryResponse;
import com.reused.user.entity.User;

/**
 * 기존 회원이면 status=LOGIN + accessToken, 신규면 status=SIGNUP_REQUIRED + signupToken이다.
 * Refresh Token은 바디가 아니라 HttpOnly 쿠키로 나간다(카카오 로그인 명세).
 */
public record KakaoLoginResponse(
		LoginStatus status,
		String accessToken,
		String signupToken,
		UserSummaryResponse user) {

	public enum LoginStatus {
		LOGIN,
		SIGNUP_REQUIRED
	}

	public static KakaoLoginResponse login(String accessToken, User user) {
		return new KakaoLoginResponse(LoginStatus.LOGIN, accessToken, null, UserSummaryResponse.from(user));
	}

	public static KakaoLoginResponse signupRequired(String signupToken) {
		return new KakaoLoginResponse(LoginStatus.SIGNUP_REQUIRED, null, signupToken, null);
	}

}
