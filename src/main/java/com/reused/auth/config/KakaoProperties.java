package com.reused.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 카카오 OAuth 연동 설정. 타임아웃은 NFR-EXT-002(MUST, 외부 호출 제한 시간)를 따른다 — 연결 3초, 읽기 5초.
 */
@ConfigurationProperties(prefix = "app.kakao")
public record KakaoProperties(
		String clientId,
		String clientSecret,
		String tokenUri,
		String userInfoUri,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("5s") Duration readTimeout) {
}
