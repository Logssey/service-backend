package com.reused.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 이메일 계정 정책 값. 기본값은 business-rules.md 4장 정책 표와 같다.
 *
 * <ul>
 *   <li>인증·재설정 코드: 10분, 코드당 검증 5회
 *   <li>코드 재발송: 60초 간격, 시간당 5회
 *   <li>로그인 실패: 동일 계정 10분당 5회
 * </ul>
 */
@ConfigurationProperties(prefix = "app.auth.local")
public record LocalAuthProperties(
		@DefaultValue("10m") Duration codeTtl,
		@DefaultValue("5") int codeMaxAttempts,
		@DefaultValue("60s") Duration resendGap,
		@DefaultValue("5") int resendLimit,
		@DefaultValue("1h") Duration resendWindow,
		@DefaultValue("5") int loginFailLimit,
		@DefaultValue("10m") Duration loginFailWindow) {
}
