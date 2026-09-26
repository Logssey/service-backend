package com.reused.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.reused.notification.api.NotificationEvent;
import com.reused.notification.service.TransactionalNotificationEventPublisher.NoticeBroadcastRequested;
import com.reused.notification.service.TransactionalNotificationEventPublisher.NotificationRequested;

/**
 * 업무 트랜잭션이 커밋된 뒤 알림을 저장한다. 롤백되면 호출되지 않는다. 트랜잭션 밖에서 발행하면 즉시 호출된다.
 *
 * <p>예외를 전부 삼킨다(ADR-014 "알림 발송 실패가 원인이 된 업무 처리를 되돌리지 않는다"). 트랜잭션 밖 발행에서
 * 예외가 새면 호출한 API가 500이 되기 때문이다. 로그에는 식별자만 남기고 본문은 남기지 않는다(NFR-LOG-003).
 *
 * <p>이 메서드들에는 {@code @Transactional}을 달지 않는다. 저장 트랜잭션은 {@link NotificationWriter}가 새로 연다.
 *
 * <p>커밋 뒤 콜백은 업무 트랜잭션의 커넥션을 아직 쥐고 있으므로 저장하는 동안 요청 하나가 커넥션을 두 개 잡는다
 * (0단계 계약 §6의 구조). 알림을 발행하는 요청이 풀 크기만큼 동시에 커밋하면 대기가 생긴다. 그런 경로가 생기면
 * 비동기 저장이나 풀 크기 조정을 검토한다.
 */
@Component
public class NotificationEventListener {

	private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

	private final NotificationWriter writer;

	public NotificationEventListener(NotificationWriter writer) {
		this.writer = writer;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onNotificationRequested(NotificationRequested requested) {
		NotificationEvent event = requested.event();
		try {
			writer.create(event);
		}
		catch (RuntimeException e) {
			log.warn("알림 저장 실패. 업무 처리는 유지된다. recipientId={}, type={}", event.recipientId(), event.type(), e);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onNoticeBroadcastRequested(NoticeBroadcastRequested requested) {
		try {
			int created = writer.broadcastNotice(requested.noticeId(), requested.noticeTitle());
			log.info("공지 알림 {}건 발송. noticeId={}", created, requested.noticeId());
		}
		catch (RuntimeException e) {
			log.warn("공지 알림 발송 실패. 공지 등록은 유지된다. noticeId={}", requested.noticeId(), e);
		}
	}

}
