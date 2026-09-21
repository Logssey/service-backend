package com.reused.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

	boolean existsByNickname(String nickname);

}
