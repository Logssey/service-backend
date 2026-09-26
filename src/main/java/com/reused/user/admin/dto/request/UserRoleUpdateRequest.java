package com.reused.user.admin.dto.request;

import com.reused.user.entity.UserRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 역할 부여·회수(회원 역할 변경 명세). USER·ADMIN 밖의 값은 역직렬화 실패로 400이다.
 *
 * @param reason 사유 필수. 앞뒤 공백을 떼고 저장한다(user_status_histories.reason VARCHAR(500) NOT NULL)
 */
public record UserRoleUpdateRequest(
		@NotNull UserRole role,
		@NotBlank @Size(max = 500) String reason) {
}
