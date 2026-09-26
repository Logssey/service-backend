package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.reused.user.config.ModerationSchedulingConfig;
import com.reused.user.service.SuspensionExpiryJob;

/**
 * 예약 실행 스위치. DB 없이 작업 대역만 두고 {@code @Scheduled}가 실제로 실행되는지 본다.
 * 운영 설정 값({@code 60s})이 간격으로 해석되는지도 함께 확인한다.
 */
class ModerationSchedulingConfigTest {

	private final SuspensionExpiryJob job = mock(SuspensionExpiryJob.class);

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ModerationSchedulingConfig.class)
			.withBean(SuspensionExpiryJob.class, () -> job);

	@Test
	@DisplayName("expiry-job-enabled=true면 작업이 예약되어 바로 한 번 실행된다")
	void enabledSchedulesJob() {
		runner.withPropertyValues("app.moderation.expiry-job-enabled=true",
						"app.moderation.expiry-check-interval=60s")
				.run(context -> {
					assertThat(context).hasSingleBean(ModerationSchedulingConfig.class);
					verify(job, timeout(5000)).releaseExpired();
				});
	}

	@Test
	@DisplayName("설정이 없으면 켜진 것으로 본다(운영 기본값)")
	void missingPropertyMeansEnabled() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(ModerationSchedulingConfig.class);
			verify(job, timeout(5000)).releaseExpired();
		});
	}

	@Test
	@DisplayName("expiry-job-enabled=false면 예약하지 않는다")
	void disabledDoesNotSchedule() {
		runner.withPropertyValues("app.moderation.expiry-job-enabled=false")
				.run(context -> {
					assertThat(context).doesNotHaveBean(ModerationSchedulingConfig.class);
					verify(job, after(500).never()).releaseExpired();
				});
	}

}
