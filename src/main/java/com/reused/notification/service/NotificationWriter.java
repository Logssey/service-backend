package com.reused.notification.service;

import java.sql.Types;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.reused.notification.api.NotificationEvent;
import com.reused.notification.api.NotificationType;

/**
 * 알림 저장. 업무 트랜잭션이 커밋된 뒤 {@link NotificationEventListener}가 부른다.
 *
 * <p>REQUIRES_NEW여야 한다. 커밋 뒤 콜백에서 기존 트랜잭션에 참여하면 이미 커밋된 트랜잭션이라 저장되지 않는다
 * (Spring {@code TransactionSynchronization#afterCommit} 문서).
 *
 * <p>한 건 저장은 팀 공용 {@link NotificationService#createFor}에 맡긴다. 수신자 없음·탈퇴와 유형별 설정(행이 없으면 켜짐) 필터가
 * 그 SQL 한 문장에 있다. 공지 전체 발송만 같은 조건의 INSERT…SELECT를 여기서 직접 쓴다.
 */
@Component
public class NotificationWriter {

	/** notifications.body VARCHAR(500) */
	static final int BODY_MAX_LENGTH = 500;

	/**
	 * 탈퇴 판정은 {@code User#isWithdrawn()}과 같다. 설정 행이 없는 회원도 포함한다.
	 * notification_id는 ALWAYS IDENTITY라 넣지 않는다.
	 */
	private static final String NOTICE_BROADCAST_SQL = """
			INSERT INTO notifications (user_id, type, title, body, target_type, target_id, created_at)
			SELECT u.user_id, 'NOTICE_PUBLISHED', ?, ?, 'NOTICE', ?, now()
			FROM users u
			LEFT JOIN notification_settings s ON s.user_id = u.user_id
			WHERE u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL AND COALESCE(s.notice_enabled, TRUE)""";

	private final NotificationService notificationService;
	private final JdbcTemplate jdbcTemplate;

	public NotificationWriter(NotificationService notificationService, JdbcTemplate jdbcTemplate) {
		this.notificationService = notificationService;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void create(NotificationEvent event) {
		notificationService.createFor(event.recipientId(), event.type().name(), titleOf(event.type()),
				truncate(event.body(), BODY_MAX_LENGTH),
				event.targetType() == null ? null : event.targetType().name(), event.targetId());
	}

	/**
	 * 공지 전체 발송. 회원 수만큼 행을 한 문장으로 넣는다. 등록한 관리자 본인도 대상이다("전체 사용자").
	 *
	 * @return 만든 알림 수
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int broadcastNotice(Long noticeId, String noticeTitle) {
		return jdbcTemplate.update(NOTICE_BROADCAST_SQL,
				new Object[] { titleOf(NotificationType.NOTICE_PUBLISHED), truncate(noticeTitle, BODY_MAX_LENGTH),
						noticeId },
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.BIGINT });
	}

	/**
	 * 문서(알림 목록 조회 명세·와이어프레임 NOTI-001)에 있는 문구를 쓰고, 없는 유형은 0단계 계약의 문구를 쓴다.
	 */
	static String titleOf(NotificationType type) {
		return switch (type) {
			case TRADE_REQUESTED -> "새 거래 요청이 도착했습니다";
			case TRADE_ACCEPTED -> "거래 요청이 승인되었습니다";
			case TRADE_REJECTED -> "거래 요청이 거절되었습니다";
			case TRADE_COMPLETED -> "거래가 완료되었습니다";
			case TRADE_CANCELED -> "거래가 취소되었습니다";
			case CHAT_RECEIVED -> "새 메시지가 도착했습니다";
			case REVIEW_RECEIVED -> "후기가 등록되었습니다";
			case REPORT_RESOLVED -> "신고 처리가 완료되었습니다";
			case NOTICE_PUBLISHED -> "공지사항이 등록되었습니다";
		};
	}

	/**
	 * PostgreSQL VARCHAR 길이는 문자(코드 포인트) 수다. 서로게이트 쌍 가운데를 자르지 않는다.
	 */
	static String truncate(String value, int maxCodePoints) {
		if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
	}

}
