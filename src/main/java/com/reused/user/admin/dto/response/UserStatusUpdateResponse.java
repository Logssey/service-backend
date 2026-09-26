package com.reused.user.admin.dto.response;

import java.time.Instant;

import com.reused.user.entity.UserStatus;
import com.reused.user.service.UserModerationService.ModerationResult;

/**
 * @param suspendedUntil 무기한 정지와 ACTIVE는 null
 */
public record UserStatusUpdateResponse(Long userId, UserStatus status, Instant suspendedUntil) {

	public static UserStatusUpdateResponse from(ModerationResult result) {
		return new UserStatusUpdateResponse(result.userId(), result.status(), result.suspendedUntil());
	}

}
