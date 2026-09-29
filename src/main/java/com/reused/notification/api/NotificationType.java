package com.reused.notification.api;

/**
 * 알림 유형 9개(알림 목록 조회 명세). notifications.type VARCHAR(30)에 저장한다. 제목 문구는 알림 모듈이 정한다.
 */
public enum NotificationType {
	TRADE_REQUESTED,
	TRADE_ACCEPTED,
	TRADE_REJECTED,
	TRADE_COMPLETED,
	TRADE_CANCELED,
	CHAT_RECEIVED,
	REVIEW_RECEIVED,
	REPORT_RESOLVED,
	NOTICE_PUBLISHED
}
