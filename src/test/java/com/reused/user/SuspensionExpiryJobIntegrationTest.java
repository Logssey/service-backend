package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.user.config.ModerationSchedulingConfig;
import com.reused.user.service.SuspensionExpiryJob;

/**
 * 정지 만료 해제 작업. 테스트 설정은 예약 실행을 끄므로 메서드를 직접 부른다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SuspensionExpiryJobIntegrationTest {

	@Autowired
	private SuspensionExpiryJob job;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ApplicationContext applicationContext;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		// 스케줄러 스레드처럼 요청 밖에서 실행한다
		RequestContextHolder.resetRequestAttributes();
	}

	@AfterEach
	void clearRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	@DisplayName("기간이 끝난 정지만 ACTIVE로 되돌리고 시스템 이력과 USER_ACTIVATE(EXPIRY) 감사를 남긴다")
	void releasesOnlyExpiredSuspensions() {
		long expired = insertUser("만료회원", "status = 'SUSPENDED', suspended_until = now() - interval '1 minute'");
		long timed = insertUser("정지회원", "status = 'SUSPENDED', suspended_until = now() + interval '1 day'");
		long indefinite = insertUser("무기한회원", "status = 'SUSPENDED', suspended_until = NULL");
		long active = insertUser("일반회원", "status = 'ACTIVE'");

		int released = job.releaseExpired();

		assertThat(released).isEqualTo(1);
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, suspended_until, updated_at FROM users WHERE user_id = ?", expired);
		assertThat(row.get("status")).isEqualTo("ACTIVE");
		assertThat(row.get("suspended_until")).isNull();
		assertThat(row.get("updated_at")).isNotNull();
		assertThat(status(timed)).isEqualTo("SUSPENDED");
		assertThat(status(indefinite)).isEqualTo("SUSPENDED");
		assertThat(status(active)).isEqualTo("ACTIVE");

		List<Map<String, Object>> histories = jdbcTemplate.queryForList(
				"SELECT user_id, change_type, before_value, after_value, reason, changed_by FROM user_status_histories");
		assertThat(histories).singleElement().satisfies(history -> assertThat(history)
				.containsEntry("user_id", expired).containsEntry("change_type", "STATUS")
				.containsEntry("before_value", "SUSPENDED").containsEntry("after_value", "ACTIVE")
				.containsEntry("reason", "정지 기간 만료")
				.containsEntry("changed_by", null));

		List<Map<String, Object>> audits = jdbcTemplate.queryForList("SELECT action, actor_id, target_type, target_id, "
				+ "result, ip_address::text AS ip, detail->>'source' AS source FROM audit_logs");
		assertThat(audits).singleElement().satisfies(audit -> assertThat(audit)
				.containsEntry("action", "USER_ACTIVATE").containsEntry("actor_id", null)
				.containsEntry("target_type", "USER").containsEntry("target_id", expired)
				.containsEntry("result", "SUCCESS").containsEntry("ip", null)
				.containsEntry("source", "EXPIRY"));
	}

	@Test
	@DisplayName("다시 실행하면 해제할 대상이 없어 0이고 이력·감사가 늘지 않는다")
	void secondRunIsNoOp() {
		insertUser("만료회원", "status = 'SUSPENDED', suspended_until = now() - interval '1 minute'");
		job.releaseExpired();

		assertThat(job.releaseExpired()).isZero();

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_status_histories", Long.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("여러 명이 만료되었으면 한 번에 모두 해제한다")
	void releasesAllExpired() {
		insertUser("만료1", "status = 'SUSPENDED', suspended_until = now() - interval '1 day'");
		insertUser("만료2", "status = 'SUSPENDED', suspended_until = now() - interval '1 second'");

		assertThat(job.releaseExpired()).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE status = 'ACTIVE'", Long.class))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("테스트 설정(expiry-job-enabled=false)에서는 정지 만료 작업이 예약되지 않는다(다른 예약 작업은 있을 수 있다)")
	void schedulingIsDisabledInTests() {
		assertThat(applicationContext.getBeanNamesForType(ModerationSchedulingConfig.class)).isEmpty();
		ScheduledAnnotationBeanPostProcessor scheduler = applicationContext.getBean(ScheduledAnnotationBeanPostProcessor.class);
		assertThat(scheduler.getScheduledTasks()).extracting(ScheduledTask::toString)
				.noneMatch(t -> t.contains("ModerationSchedulingConfig") || t.contains("SuspensionExpiryJob"));
	}

	private long insertUser(String nickname, String state) {
		Long userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
		jdbcTemplate.update("UPDATE users SET " + state + " WHERE user_id = ?", userId);
		return userId;
	}

	private String status(long userId) {
		return jdbcTemplate.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, userId);
	}

}
