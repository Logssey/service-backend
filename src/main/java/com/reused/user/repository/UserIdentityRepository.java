package com.reused.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserIdentity;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

	Optional<UserIdentity> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

	/**
	 * 이메일은 LOCAL 범위에서만 유일하다(부분 UNIQUE 인덱스). provider를 함께 조건에 넣어야 한다.
	 */
	Optional<UserIdentity> findByProviderAndEmail(AuthProvider provider, String email);

	boolean existsByProviderAndEmail(AuthProvider provider, String email);

	/**
	 * 1차 릴리스는 회원당 인증 수단이 하나이므로 단건으로 조회한다(ADR-017).
	 */
	Optional<UserIdentity> findByUserId(Long userId);

}
