package com.reused.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.user.entity.NotificationSettings;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, Long> {
}
