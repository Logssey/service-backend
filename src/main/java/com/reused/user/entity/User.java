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
 * 소셜 로그인으로 가입한 회원. 컬럼 구성은 schema/001_init.sql의 users 테이블과 1:1로 맞춘다.
 * 비밀번호 컬럼이 없는 것은 의도된 설계다(ADR-004, 비밀번호 인증은 범위 밖).
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AuthProvider provider;

	@Column(name = "provider_user_id", nullable = false, length = 255)
	private String providerUserId;

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

	private User(AuthProvider provider, String providerUserId, String nickname, Instant termsAgreedAt) {
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.nickname = nickname;
		this.role = UserRole.USER;
		this.status = UserStatus.ACTIVE;
		this.termsAgreedAt = termsAgreedAt;
		this.createdAt = Instant.now();
	}

	public static User signUp(AuthProvider provider, String providerUserId, String nickname, Instant termsAgreedAt) {
		return new User(provider, providerUserId, nickname, termsAgreedAt);
	}

	public boolean isWithdrawn() {
		return withdrawnAt != null || status == UserStatus.WITHDRAWN;
	}

	public boolean isSuspended() {
		return status == UserStatus.SUSPENDED;
	}

}
