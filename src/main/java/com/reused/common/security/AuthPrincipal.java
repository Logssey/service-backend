package com.reused.common.security;

import com.reused.user.entity.UserRole;

/**
 * 인증된 요청 주체. 컨트롤러는 {@link AuthUser}로 이 값을 받는다.
 */
public record AuthPrincipal(Long userId, UserRole role) {

	public boolean isAdmin() {
		return role == UserRole.ADMIN;
	}

}
