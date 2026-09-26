package com.reused.notification.service;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.reused.notification.api.NotificationEvent;
import com.reused.notification.api.NotificationEventPublisher;

/**
 * 발행 요청을 내부 이벤트로 바꿔 던진다. 저장은 {@link NotificationEventListener}가 커밋 뒤에 한다.
 *
 * <p>내부 이벤트 타입은 이 패키지 밖에서 보이지 않는다. 다른 도메인이 이 인터페이스를 거치지 않고
 * {@code ApplicationEventPublisher}로 직접 알림을 만들 수 없게 하기 위해서다.
 */
@Component
public class TransactionalNotificationEventPublisher implements NotificationEventPublisher {

	private final ApplicationEventPublisher eventPublisher;

	public TransactionalNotificationEventPublisher(ApplicationEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	@Override
	public void publish(NotificationEvent event) {
		Objects.requireNonNull(event, "event");
		eventPublisher.publishEvent(new NotificationRequested(event));
	}

	@Override
	public void publishNoticeToAll(Long noticeId, String noticeTitle) {
		Objects.requireNonNull(noticeId, "noticeId");
		eventPublisher.publishEvent(new NoticeBroadcastRequested(noticeId, noticeTitle));
	}

	record NotificationRequested(NotificationEvent event) {
	}

	record NoticeBroadcastRequested(Long noticeId, String noticeTitle) {
	}

}
