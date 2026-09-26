package com.reused.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.notification.api.NotificationEvent;
import com.reused.notification.api.NotificationEventPublisher;
import com.reused.notification.api.NotificationTargetType;
import com.reused.notification.api.NotificationType;

/**
 * 알림 발행 파이프라인. 업무 트랜잭션은 TransactionTemplate으로 흉내 내고, 커밋 뒤의 저장 결과를 DB에서 확인한다.
 * 테스트 메서드에 {@code @Transactional}을 달지 않는다(커밋이 일어나야 한다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationPublisherIntegrationTest {

	private static final long MISSING_USER_ID = 999L;

	@Autowired
	private NotificationEventPublisher publisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private TransactionTemplate tx;
	private long recipient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE notifications, audit_logs, notification_settings, user_status_histories, "
				+ "user_identities, users RESTART IDENTITY CASCADE");
		tx = new TransactionTemplate(transactionManager);
		recipient = insertUser("수신자", true);
	}

	// --- 트랜잭션 경계 ---

	@Test
	@DisplayName("업무 트랜잭션이 커밋되면 그 뒤에 알림 한 건이 저장된다. 커밋 전에는 없다")
	void committedTransactionCreatesNotification() {
		tx.executeWithoutResult(status -> {
			publisher.publish(tradeAccepted(recipient, 10L));
			assertThat(count()).as("커밋 전").isZero();
		});

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT user_id, type, title, body, target_type, "
				+ "target_id, read_at, created_at FROM notifications");
		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row).containsEntry("user_id", recipient).containsEntry("type", "TRADE_ACCEPTED")
					.containsEntry("title", "거래 요청이 승인되었습니다").containsEntry("body", "아이패드 프로 11인치")
					.containsEntry("target_type", "TRADE").containsEntry("target_id", 10L)
					.containsEntry("read_at", null);
			assertThat(row.get("created_at")).isNotNull();
		});
	}

	@Test
	@DisplayName("업무 트랜잭션이 롤백되면 알림을 만들지 않는다")
	void rolledBackTransactionCreatesNothing() {
		tx.executeWithoutResult(status -> {
			publisher.publish(tradeAccepted(recipient, 10L));
			status.setRollbackOnly();
		});

		assertThat(count()).isZero();
	}

	@Test
	@DisplayName("트랜잭션 밖에서 발행하면 즉시 저장한다")
	void publishOutsideTransactionStoresImmediately() {
		publisher.publish(tradeAccepted(recipient, 10L));

		assertThat(count()).isEqualTo(1);
	}

	// --- 수신자 규칙 ---

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({
			"TRADE_REQUESTED, trade_enabled",
			"TRADE_ACCEPTED, trade_enabled",
			"TRADE_REJECTED, trade_enabled",
			"TRADE_COMPLETED, trade_enabled",
			"TRADE_CANCELED, trade_enabled",
			"CHAT_RECEIVED, chat_enabled",
			"REVIEW_RECEIVED, review_enabled",
			"REPORT_RESOLVED, report_enabled",
			"NOTICE_PUBLISHED, notice_enabled" })
	@DisplayName("유형에 맞는 설정이 꺼져 있으면 만들지 않고, 켜면 만든다")
	void settingFiltersByType(NotificationType type, String column) {
		jdbcTemplate.update("UPDATE notification_settings SET " + column + " = false WHERE user_id = ?", recipient);

		publisher.publish(new NotificationEvent(recipient, type, "본문", NotificationTargetType.TRADE, 1L));
		assertThat(count()).isZero();

		jdbcTemplate.update("UPDATE notification_settings SET " + column + " = true WHERE user_id = ?", recipient);
		publisher.publish(new NotificationEvent(recipient, type, "본문", NotificationTargetType.TRADE, 1L));
		assertThat(count()).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 유형의 설정은 영향을 주지 않는다")
	void otherSettingsDoNotApply() {
		jdbcTemplate.update("UPDATE notification_settings SET chat_enabled = false, review_enabled = false, "
				+ "report_enabled = false, notice_enabled = false WHERE user_id = ?", recipient);

		publisher.publish(tradeAccepted(recipient, 10L));

		assertThat(count()).isEqualTo(1);
	}

	@Test
	@DisplayName("설정 행이 없으면 모두 켜진 것으로 본다")
	void missingSettingsRowMeansEnabled() {
		long noSettings = insertUser("설정없음", false);

		publisher.publish(tradeAccepted(noSettings, 10L));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Long.class,
				noSettings)).isEqualTo(1);
	}

	@Test
	@DisplayName("수신자가 없거나 탈퇴했으면 만들지 않고 예외도 없다. 정지 회원에게는 만든다")
	void recipientState() {
		long withdrawn = insertUser("탈퇴", true);
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", withdrawn);
		long timestampOnly = insertUser("시각만", true);
		jdbcTemplate.update("UPDATE users SET withdrawn_at = now() WHERE user_id = ?", timestampOnly);
		long suspended = insertUser("정지", true);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspended);

		assertThatCode(() -> tx.executeWithoutResult(status -> {
			publisher.publish(tradeAccepted(withdrawn, 10L));
			publisher.publish(tradeAccepted(timestampOnly, 10L));
			publisher.publish(tradeAccepted(MISSING_USER_ID, 10L));
			publisher.publish(tradeAccepted(suspended, 10L));
		})).doesNotThrowAnyException();

		assertThat(jdbcTemplate.queryForList("SELECT user_id FROM notifications", Long.class)).containsExactly(suspended);
	}

	@Test
	@DisplayName("같은 유형·같은 대상이어도 중복 억제하지 않고 매번 만든다")
	void sameTargetIsNotDeduplicated() {
		publisher.publish(tradeAccepted(recipient, 10L));
		publisher.publish(tradeAccepted(recipient, 10L));

		assertThat(count()).isEqualTo(2);
	}

	// --- 문구 ---

	@ParameterizedTest(name = "{0}")
	@CsvSource({
			"TRADE_REQUESTED, 새 거래 요청이 도착했습니다",
			"TRADE_ACCEPTED, 거래 요청이 승인되었습니다",
			"TRADE_REJECTED, 거래 요청이 거절되었습니다",
			"TRADE_COMPLETED, 거래가 완료되었습니다",
			"TRADE_CANCELED, 거래가 취소되었습니다",
			"CHAT_RECEIVED, 새 메시지가 도착했습니다",
			"REVIEW_RECEIVED, 후기가 등록되었습니다",
			"REPORT_RESOLVED, 신고 처리가 완료되었습니다",
			"NOTICE_PUBLISHED, 공지사항이 등록되었습니다" })
	@DisplayName("제목은 유형별 고정 문구다")
	void titleIsFixedPerType(NotificationType type, String title) {
		publisher.publish(new NotificationEvent(recipient, type, null, null, null));

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, body, target_type, target_id FROM notifications");
		assertThat(row).containsEntry("title", title).containsEntry("body", null)
				.containsEntry("target_type", null).containsEntry("target_id", null);
	}

	@Test
	@DisplayName("body는 500자(코드 포인트)를 넘으면 잘린다. 서로게이트 쌍을 가르지 않는다")
	void bodyIsTruncated() {
		publisher.publish(new NotificationEvent(recipient, NotificationType.REPORT_RESOLVED, "가".repeat(600),
				NotificationTargetType.REPORT, 1L));
		publisher.publish(new NotificationEvent(recipient, NotificationType.REPORT_RESOLVED, "😀".repeat(501),
				NotificationTargetType.REPORT, 2L));

		assertThat(jdbcTemplate.queryForList("SELECT char_length(body) FROM notifications ORDER BY notification_id",
				Integer.class)).containsExactly(500, 500);
		assertThat(jdbcTemplate.queryForObject("SELECT body FROM notifications WHERE target_id = 2", String.class))
				.isEqualTo("😀".repeat(500));
	}

	@Test
	@DisplayName("targetId만 있고 targetType이 없는 이벤트는 만들 때 거절한다(호출 코드의 오류)")
	void eventRequiresTargetTypeWithTargetId() {
		assertThatThrownBy(() -> new NotificationEvent(recipient, NotificationType.TRADE_ACCEPTED, null, null, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NotificationEvent(null, NotificationType.TRADE_ACCEPTED, null, null, null))
				.isInstanceOf(NullPointerException.class);
	}

	// --- 공지 전체 발송 ---

	@Test
	@DisplayName("공지 발송은 커밋 뒤 탈퇴자와 공지 알림을 끈 회원을 빼고 전원에게 한 건씩 만든다. 설정 행이 없어도 받는다")
	void noticeBroadcastRespectsSettingsAndWithdrawal() {
		long noticeOff = insertUser("공지끔", true);
		jdbcTemplate.update("UPDATE notification_settings SET notice_enabled = false WHERE user_id = ?", noticeOff);
		long noSettings = insertUser("설정없음", false);
		long withdrawn = insertUser("탈퇴", true);
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", withdrawn);
		long timestampOnly = insertUser("시각만", true);
		jdbcTemplate.update("UPDATE users SET withdrawn_at = now() WHERE user_id = ?", timestampOnly);
		long suspended = insertUser("정지", true);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspended);

		tx.executeWithoutResult(status -> {
			publisher.publishNoticeToAll(42L, "서비스 점검 안내");
			assertThat(count()).as("커밋 전").isZero();
		});

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT user_id, type, title, body, target_type, "
				+ "target_id, read_at, created_at FROM notifications ORDER BY user_id");
		assertThat(rows).extracting(row -> row.get("user_id")).containsExactly(recipient, noSettings, suspended);
		assertThat(rows).allSatisfy(row -> {
			assertThat(row).containsEntry("type", "NOTICE_PUBLISHED").containsEntry("title", "공지사항이 등록되었습니다")
					.containsEntry("body", "서비스 점검 안내").containsEntry("target_type", "NOTICE")
					.containsEntry("target_id", 42L).containsEntry("read_at", null);
			assertThat(row.get("created_at")).isNotNull();
		});
	}

	@Test
	@DisplayName("공지 등록 트랜잭션이 롤백되면 발송하지 않는다. 트랜잭션 밖이면 즉시 발송한다")
	void noticeBroadcastFollowsTransaction() {
		tx.executeWithoutResult(status -> {
			publisher.publishNoticeToAll(42L, "롤백될 공지");
			status.setRollbackOnly();
		});
		assertThat(count()).isZero();

		publisher.publishNoticeToAll(43L, "바로 발송");
		assertThat(count()).isEqualTo(1);
	}

	@Test
	@DisplayName("공지 제목이 500자를 넘으면 body에서 잘린다")
	void noticeTitleIsTruncated() {
		publisher.publishNoticeToAll(42L, "가".repeat(600));

		assertThat(jdbcTemplate.queryForObject("SELECT char_length(body) FROM notifications", Integer.class))
				.isEqualTo(500);
	}

	// --- 실패 격리 ---

	@Test
	@DisplayName("알림 저장이 실패해도 업무 트랜잭션은 커밋되고 호출자에게 예외가 전파되지 않는다")
	void storageFailureDoesNotAffectBusinessTransaction() {
		jdbcTemplate.execute("ALTER TABLE notifications RENAME TO notifications_unavailable");
		try {
			assertThatCode(() -> tx.executeWithoutResult(status -> {
				jdbcTemplate.update("UPDATE users SET bio = '업무 반영' WHERE user_id = ?", recipient);
				publisher.publish(tradeAccepted(recipient, 10L));
				publisher.publish(chat(recipient, 5L));
				publisher.publishNoticeToAll(42L, "공지");
			})).doesNotThrowAnyException();

			assertThatCode(() -> {
				publisher.publish(tradeAccepted(recipient, 10L));
				publisher.publishNoticeToAll(43L, "공지");
			}).as("트랜잭션 밖 발행").doesNotThrowAnyException();
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE notifications_unavailable RENAME TO notifications");
		}

		assertThat(jdbcTemplate.queryForObject("SELECT bio FROM users WHERE user_id = ?", String.class, recipient))
				.isEqualTo("업무 반영");
		assertThat(count()).isZero();

		// 복구 뒤에는 다시 정상 저장된다
		publisher.publish(tradeAccepted(recipient, 10L));
		assertThat(count()).isEqualTo(1);
	}

	// --- helpers ---

	private long insertUser(String nickname, boolean withSettings) {
		Long userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
		if (withSettings) {
			jdbcTemplate.update("INSERT INTO notification_settings (user_id) VALUES (?)", userId);
		}
		return userId;
	}

	private long count() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class);
	}

	private static NotificationEvent tradeAccepted(long recipientId, long tradeId) {
		return new NotificationEvent(recipientId, NotificationType.TRADE_ACCEPTED, "아이패드 프로 11인치",
				NotificationTargetType.TRADE, tradeId);
	}

	private static NotificationEvent chat(long recipientId, long chatRoomId) {
		return new NotificationEvent(recipientId, NotificationType.CHAT_RECEIVED, "아이패드 프로 11인치",
				NotificationTargetType.CHAT_ROOM, chatRoomId);
	}

}
