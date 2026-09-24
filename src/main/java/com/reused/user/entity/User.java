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

	public boolean isSuspended() {
		return status == UserStatus.SUSPENDED;
	}

}
