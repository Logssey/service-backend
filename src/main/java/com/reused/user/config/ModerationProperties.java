package com.reused.user.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 이용정지 정책 값(business-rules 4장 "이용정지 기본 기간 7일").
 *
 * @param defaultSuspension 신고 처리의 SUSPEND_USER가 적용하는 기간. 관리자 상태 변경은 요청 값을 쓴다
 * @param expiryCheckInterval 만료된 정지를 해제하는 작업의 실행 간격
 * @param expiryJobEnabled false면 작업을 예약하지 않는다. 테스트는 false로 두고 메서드를 직접 부른다
 */
@ConfigurationProperties(prefix = "app.moderation")
public record ModerationProperties(
		@DefaultValue("7d") Duration defaultSuspension,
		@DefaultValue("60s") Duration expiryCheckInterval,
		@DefaultValue("true") boolean expiryJobEnabled) {
}
