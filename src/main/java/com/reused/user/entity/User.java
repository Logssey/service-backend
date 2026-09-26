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
 * 회원. 컬럼 구성은 schema/001_init.sql의 users 테이블과 1:1로 맞춘다.
 *
 * <p>인증 수단(카카오 회원번호, 이메일·비밀번호)은 이 엔티티에 없다.
 * {@link UserIdentity}로 분리되어 있으며 users는 사람만 표현한다(ADR-017).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	/**
	 * 탈퇴 회원 닉네임 접두어. 탈퇴 시 {@code UserProfileLifecycleService}의 JDBC 탈퇴 처리가 붙이고,
	 * 가입·닉네임 확인에서는 {@code NicknamePolicy}가 예약어로 막는다.
	 */
	public static final String WITHDRAWN_NICKNAME_PREFIX = "탈퇴회원#";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	private Long id;

	@Column(nullable = false, length = 20)
	private String nickname;

	@Column(name = "profile_image_url")
	private String profileImageUrl;

	@Column(length = 200)
	private String bio;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private UserRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	@Column(name = "suspended_until")
	private Instant suspendedUntil;

	@Column(name = "terms_agreed_at", nullable = false)
	private Instant termsAgreedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "withdrawn_at")
	private Instant withdrawnAt;

	private User(String nickname, Instant termsAgreedAt) {
		this.nickname = nickname;
		this.role = UserRole.USER;
		this.status = UserStatus.ACTIVE;
		this.termsAgreedAt = termsAgreedAt;
		this.createdAt = Instant.now();
	}

	public static User signUp(String nickname, Instant termsAgreedAt) {
		return new User(nickname, termsAgreedAt);
	}

	public boolean isWithdrawn() {
		return withdrawnAt != null || status == UserStatus.WITHDRAWN;
	}

	/**
	 * DB 상태값이 SUSPENDED인지만 본다. 기간이 지난 정지도 true이므로 이용 차단 판정에는 쓰지 않는다.
	 * 판정은 {@link #isSuspendedAt(Instant)}로 한다.
	 */
	public boolean isSuspended() {
		return status == UserStatus.SUSPENDED;
	}

	/**
	 * 지금 이용정지 중인가. suspended_until이 NULL이면 무기한이고(001_init.sql 주석), 지난 기간은 해제로 본다.
	 * 자동 해제 작업이 상태값을 늦게 되돌려도 이 판정은 정확하다. {@link #isSuspended()}는 상태값만 보므로 작업이 돌 때까지 정지로 남는다.
	 */
	public boolean isSuspendedAt(Instant now) {
		return status == UserStatus.SUSPENDED && (suspendedUntil == null || suspendedUntil.isAfter(now));
	}

	/**
	 * @param until 정지 종료 시각. null이면 무기한
	 */
	public void suspend(Instant until, Instant now) {
		this.status = UserStatus.SUSPENDED;
		this.suspendedUntil = until;
		this.updatedAt = now;
	}

	public void activate(Instant now) {
		this.status = UserStatus.ACTIVE;
		this.suspendedUntil = null;
		this.updatedAt = now;
	}

	public void changeRole(UserRole role, Instant now) {
		this.role = role;
		this.updatedAt = now;
	}

}
