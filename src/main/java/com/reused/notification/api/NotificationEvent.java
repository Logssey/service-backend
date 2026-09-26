package com.reused.notification.api;

import java.util.Objects;

/**
 * 알림 한 건의 발행 요청. 제목은 유형별 고정 문구라 받지 않는다.
 *
 * <p>body에 상대방 닉네임을 넣지 않는다. 탈퇴하면 닉네임이 바뀌는데 알림에 복사된 문자열은 남기 때문이다
 * (NFR-DATA-010). 게시글 제목처럼 대상 리소스를 가리키는 문구를 쓴다. 500자를 넘으면 잘린다.
 *
 * @param body 없으면 null
 * @param targetType targetId가 있으면 필수
 */
public record NotificationEvent(Long recipientId, NotificationType type, String body,
		NotificationTargetType targetType, Long targetId) {

	public NotificationEvent {
		Objects.requireNonNull(recipientId, "recipientId");
		Objects.requireNonNull(type, "type");
		if (targetId != null && targetType == null) {
			throw new IllegalArgumentException("targetId가 있으면 targetType도 있어야 한다.");
		}
	}

}
