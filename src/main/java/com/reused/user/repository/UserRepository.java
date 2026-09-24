package com.reused.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByNickname(String nickname);

}
