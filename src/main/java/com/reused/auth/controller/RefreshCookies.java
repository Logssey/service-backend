package com.reused.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.reused.auth.service.RefreshTokenCookieFactory;

/**
 * Refresh Token은 바디가 아니라 HttpOnly 쿠키로 나간다(api-spec 0.3).
 */
final class RefreshCookies {

	private RefreshCookies() {
	}

	static ResponseEntity.BodyBuilder attach(ResponseEntity.BodyBuilder builder,
			RefreshTokenCookieFactory cookieFactory, String refreshToken) {
		if (refreshToken == null) {
			return builder;
		}
		return builder.header(HttpHeaders.SET_COOKIE, cookieFactory.create(refreshToken).toString());
	}

}
