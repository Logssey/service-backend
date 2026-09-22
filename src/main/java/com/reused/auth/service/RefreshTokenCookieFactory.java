package com.reused.auth.service;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.reused.auth.config.AuthProperties;

/**
 * Refresh Token 쿠키 생성.
 *
 * <p>설계 문서는 HttpOnly·Secure까지만 정하고 SameSite·Path·Max-Age를 규정하지 않았다.
 * 프론트와 API가 같은 Origin이라는 전제(api-spec §1) 아래 SameSite=Lax로 두어
 * 교차 사이트 요청에 쿠키가 실리지 않게 하고, 이것으로 CSRF 대응을 갈음한다.
 * Path를 인증 엔드포인트로 좁혀 다른 요청에는 아예 전송되지 않게 한다.
 */
@Component
public class RefreshTokenCookieFactory {

	private final AuthProperties properties;

	public RefreshTokenCookieFactory(AuthProperties properties) {
		this.properties = properties;
	}

	public ResponseCookie create(String token) {
		return base(token)
				.maxAge(properties.refreshTokenTtl())
				.build();
	}

	/**
	 * 로그아웃 시 즉시 만료시킨다.
	 */
	public ResponseCookie expired() {
		return base("")
				.maxAge(0)
				.build();
	}

	public String cookieName() {
		return properties.cookie().name();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(properties.cookie().name(), value)
				.httpOnly(true)
				.secure(properties.cookie().secure())
				.sameSite(properties.cookie().sameSite())
				.path(properties.cookie().path());
	}

}
