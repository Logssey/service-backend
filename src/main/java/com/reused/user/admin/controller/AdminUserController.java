package com.reused.user.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.pagination.CursorPageResponse;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.user.admin.dto.request.AdminUserSearchRequest;
import com.reused.user.admin.dto.request.UserRoleUpdateRequest;
import com.reused.user.admin.dto.request.UserStatusUpdateRequest;
import com.reused.user.admin.dto.response.AdminUserResponse;
import com.reused.user.admin.dto.response.UserRoleUpdateResponse;
import com.reused.user.admin.dto.response.UserStatusUpdateResponse;
import com.reused.user.admin.service.AdminUserQueryService;
import com.reused.user.admin.service.AdminUserService;

import jakarta.validation.Valid;

/**
 * 관리자 권한은 SecurityConfig의 URL 규칙과 {@code AdminAccessInterceptor}의 DB 재확인으로 이미 적용된다.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

	private final AdminUserQueryService queryService;
	private final AdminUserService adminUserService;

	public AdminUserController(AdminUserQueryService queryService, AdminUserService adminUserService) {
		this.queryService = queryService;
		this.adminUserService = adminUserService;
	}

	/** GET /admin/users — ADMIN */
	@GetMapping
	public CursorPageResponse<AdminUserResponse> list(@Valid AdminUserSearchRequest request) {
		return queryService.search(request);
	}

	/** PATCH /admin/users/{userId}/role — ADMIN */
	@PatchMapping("/{userId}/role")
	public UserRoleUpdateResponse changeRole(@AuthUser AuthPrincipal principal, @PathVariable Long userId,
			@Valid @RequestBody UserRoleUpdateRequest request) {
		return adminUserService.changeRole(principal.userId(), userId, request.role(), request.reason());
	}

	/** PATCH /admin/users/{userId}/status — ADMIN */
	@PatchMapping("/{userId}/status")
	public UserStatusUpdateResponse changeStatus(@AuthUser AuthPrincipal principal, @PathVariable Long userId,
			@Valid @RequestBody UserStatusUpdateRequest request) {
		return adminUserService.changeStatus(principal.userId(), userId, request.status(), request.suspendedUntil(),
				request.reason());
	}

}
