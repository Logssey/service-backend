package com.reused.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.user.entity.UserStatusHistory;

public interface UserStatusHistoryRepository extends JpaRepository<UserStatusHistory, Long> {
}
