package com.reused.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토큰 수명은 ADR-005의 확정값(Access 30분 / Refresh 14일)이고 signupToken은 10분이다.
 * 기본값을 두되 운영에서는 환경변수로 주입한다. secret은 기본값을 두지 않는다.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
		String secret,
		Duration accessTokenTtl,
		Duration refreshTokenTtl,
		Duration signupTokenTtl,
		Cookie cookie) {

	public record Cookie(String name, String path, boolean secure, String sameSite) {
	}

}
