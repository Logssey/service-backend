package com.reused.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kakao")
public record KakaoProperties(
		String clientId,
		String clientSecret,
		String tokenUri,
		String userInfoUri) {
}
