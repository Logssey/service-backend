package com.reused.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경. 새 비밀번호 길이는 가입·재설정과 같다(business-rules 4장 "최소 8자, 최대 128자").
 */
public record PasswordChangeRequest(
		@NotBlank String currentPassword,
		@NotBlank @Size(min = 8, max = 128) String newPassword) {
}
