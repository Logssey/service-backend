package com.reused.user.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.reused.user.service.SuspensionExpiryJob;

/**
 * 정지 만료 해제 작업({@code SuspensionExpiryJob})의 예약 실행. 테스트는 끄고 작업 메서드를 직접 부른다.
 *
 * <p>예약 메서드는 이 클래스에만 있다. 그래서 다른 설정(이미지 정리 작업의 {@code ImageStorageConfig})이
 * {@code @EnableScheduling}을 켜도 {@code app.moderation.expiry-job-enabled=false}이면 이 클래스가 등록되지 않아
 * 정지 만료 작업은 예약되지 않는다. 여기의 {@code @EnableScheduling}은 그 설정과 겹쳐도 해가 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.moderation.expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class ModerationSchedulingConfig {

	private final SuspensionExpiryJob job;

	public ModerationSchedulingConfig(SuspensionExpiryJob job) {
		this.job = job;
	}

	@Scheduled(fixedDelayString = "${app.moderation.expiry-check-interval:60s}")
	public void releaseExpiredSuspensions() {
		job.releaseExpired();
	}

}
