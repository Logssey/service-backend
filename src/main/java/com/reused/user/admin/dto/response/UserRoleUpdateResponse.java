package com.reused.user.admin.dto.response;

import com.reused.user.entity.UserRole;

public record UserRoleUpdateResponse(Long userId, UserRole role) {
}
