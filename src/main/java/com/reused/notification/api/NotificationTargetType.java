package com.reused.notification.api;

/**
 * 알림을 눌렀을 때 이동할 리소스 종류(notifications.target_type VARCHAR(20)).
 */
public enum NotificationTargetType {
	TRADE,
	CHAT_ROOM,
	LISTING,
	REVIEW,
	REPORT,
	NOTICE
}
