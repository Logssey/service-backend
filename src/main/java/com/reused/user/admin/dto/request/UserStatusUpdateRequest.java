package com.reused.user.admin.dto.request;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 이용정지·정지 해제(회원 상태 변경 명세).
 *
 * @param suspendedUntil SUSPENDED일 때만 보낸다. 생략하거나 null이면 무기한 정지다. 현재 시각 이전이거나
 *        ACTIVE와 함께 오면 400
 * @param reason 사유 필수. 앞뒤 공백을 떼고 저장한다
 */
public record UserStatusUpdateRequest(
		@NotNull AdminUserStatusChange status,
		Instant suspendedUntil,
		@NotBlank @Size(max = 500) String reason) {
}
