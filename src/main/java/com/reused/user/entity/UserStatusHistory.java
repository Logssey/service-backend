package com.reused.user.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 상태·역할 변경 이력(user_status_histories). 사유는 필수다(DDL 주석 "사유 필수").
 *
 * <p>정지 종료 시각을 담을 컬럼이 없다. 기간은 감사 로그 detail에 남긴다.
 * 같은 값끼리의 행(SUSPENDED→SUSPENDED, 기간만 변경)도 DDL상 허용된다.
 */
@Entity
@Table(name = "user_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStatusHistory {

	/** DB CHECK 제약(ck_user_status_hist_type)과 값이 같아야 한다. */
	public enum ChangeType {
		STATUS,
		ROLE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "history_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_type", nullable = false, length = 20)
	private ChangeType changeType;

	@Column(name = "before_value", nullable = false, length = 20)
	private String beforeValue;

	@Column(name = "after_value", nullable = false, length = 20)
	private String afterValue;

	@Column(nullable = false, length = 500)
	private String reason;

	@Column(name = "changed_by")
	private Long changedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private UserStatusHistory(Long userId, ChangeType changeType, String beforeValue, String afterValue,
			String reason, Long changedBy) {
		this.userId = userId;
		this.changeType = changeType;
		this.beforeValue = beforeValue;
		this.afterValue = afterValue;
		this.reason = reason;
		this.changedBy = changedBy;
		this.createdAt = Instant.now();
	}

	/**
	 * @param changedBy 변경한 관리자. 시스템 처리(탈퇴, 정지 기간 만료)는 null(DDL 주석)
	 */
	public static UserStatusHistory status(Long userId, UserStatus before, UserStatus after, String reason,
			Long changedBy) {
		return new UserStatusHistory(userId, ChangeType.STATUS, before.name(), after.name(), reason, changedBy);
	}

	public static UserStatusHistory role(Long userId, UserRole before, UserRole after, String reason,
			Long changedBy) {
		return new UserStatusHistory(userId, ChangeType.ROLE, before.name(), after.name(), reason, changedBy);
	}

}
