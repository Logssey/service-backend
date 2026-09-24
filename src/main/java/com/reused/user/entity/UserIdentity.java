package com.reused.user.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 수단. 회원({@link User})과 분리된 엔티티다(ADR-017).
 *
 * <p>구조상 한 회원이 여러 행을 가질 수 있으나 1차 릴리스는 애플리케이션에서 1개로 제한한다.
 * 탈퇴 시 이 행을 삭제하므로 탈퇴 회원은 구조적으로 로그인할 수 없다(ADR-018).
 *
 * <p>제공자별 컬럼 사용 규칙은 DB CHECK 제약과 같다.
 * <ul>
 *   <li>{@code KAKAO} — providerUserId=카카오 회원번호. email·passwordHash는 NULL
 *   <li>{@code LOCAL} — providerUserId=서버 발급 UUID. email·passwordHash 필수
 * </ul>
 */
@Entity
@Table(name = "user_identities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "identity_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AuthProvider provider;

	@Column(name = "provider_user_id", nullable = false, length = 255)
	private String providerUserId;

	@Column(length = 254)
	private String email;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private UserIdentity(User user, AuthProvider provider, String providerUserId, String email, String passwordHash) {
		this.user = user;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.email = email;
		this.passwordHash = passwordHash;
		this.createdAt = Instant.now();
	}

	/**
	 * @param provider 소셜 제공자. LOCAL은 {@link #local}로 만든다
	 */
	public static UserIdentity social(User user, AuthProvider provider, String providerUserId) {
		if (provider == AuthProvider.LOCAL) {
			throw new IllegalArgumentException("LOCAL 인증 수단은 local()로 만든다.");
		}
		return new UserIdentity(user, provider, providerUserId, null, null);
	}

	/**
	 * @param email 정규화(소문자·공백 제거)된 이메일
	 * @param passwordHash 이미 해시된 비밀번호. 평문을 넘기지 않는다
	 */
	public static UserIdentity local(User user, String email, String passwordHash) {
		return new UserIdentity(user, AuthProvider.LOCAL, UUID.randomUUID().toString(), email, passwordHash);
	}

	public boolean isLocal() {
		return provider == AuthProvider.LOCAL;
	}

	public boolean isEmailVerified() {
		return emailVerifiedAt != null;
	}

	public void markEmailVerified(Instant at) {
		this.emailVerifiedAt = at;
	}

	public void changePasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

}
