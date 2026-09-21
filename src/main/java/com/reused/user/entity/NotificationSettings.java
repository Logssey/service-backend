package com.reused.user.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유형별 알림 수신 설정. 회원가입 시 기본값으로 1행 생성한다(001_init.sql의 테이블 주석).
 */
@Entity
@Table(name = "notification_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSettings {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "trade_enabled", nullable = false)
	private boolean tradeEnabled;

	@Column(name = "chat_enabled", nullable = false)
	private boolean chatEnabled;

	@Column(name = "review_enabled", nullable = false)
	private boolean reviewEnabled;

	@Column(name = "report_enabled", nullable = false)
	private boolean reportEnabled;

	@Column(name = "notice_enabled", nullable = false)
	private boolean noticeEnabled;

	@Column(name = "updated_at")
	private Instant updatedAt;

	private NotificationSettings(Long userId) {
		this.userId = userId;
		this.tradeEnabled = true;
		this.chatEnabled = true;
		this.reviewEnabled = true;
		this.reportEnabled = true;
		this.noticeEnabled = true;
	}

	public static NotificationSettings defaultsFor(Long userId) {
		return new NotificationSettings(userId);
	}

}
