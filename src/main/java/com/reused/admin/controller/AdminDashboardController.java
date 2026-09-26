package com.reused.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.admin.dto.response.AdminDashboardResponse;
import com.reused.admin.service.AdminDashboardService;

/**
 * 관리자 권한은 SecurityConfig의 URL 규칙과 {@code AdminAccessInterceptor}의 DB 재확인으로 이미 적용된다.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

	private final AdminDashboardService dashboardService;

	public AdminDashboardController(AdminDashboardService dashboardService) {
		this.dashboardService = dashboardService;
	}

	/** GET /admin/dashboard — ADMIN */
	@GetMapping
	public AdminDashboardResponse dashboard() {
		return dashboardService.getDashboard();
	}

}
