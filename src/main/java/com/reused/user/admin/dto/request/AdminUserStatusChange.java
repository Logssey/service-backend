package com.reused.user.admin.dto.request;

/**
 * 관리자가 바꿀 수 있는 회원 상태. 탈퇴(WITHDRAWN)는 본인만 할 수 있어 받지 않는다.
 * 전용 enum이라 {@code "WITHDRAWN"}은 역직렬화 단계에서 400이 된다.
 */
public enum AdminUserStatusChange {
	ACTIVE,
	SUSPENDED
}
