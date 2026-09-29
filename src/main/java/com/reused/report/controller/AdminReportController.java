package com.reused.report.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.pagination.CursorPageResponse;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.report.dto.request.AdminReportSearchRequest;
import com.reused.report.dto.request.ReportHandleRequest;
import com.reused.report.dto.response.AdminReportResponse;
import com.reused.report.dto.response.ReportHandleResponse;
import com.reused.report.service.AdminReportService;

import jakarta.validation.Valid;

/**
 * 관리자 신고 API. ADMIN 권한은 SecurityConfig의 {@code /api/v1/admin/**} 규칙과 AdminAccessInterceptor가 확인한다.
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

	private final AdminReportService adminReportService;

	public AdminReportController(AdminReportService adminReportService) {
		this.adminReportService = adminReportService;
	}

	/** GET /admin/reports — ADMIN */
	@GetMapping
	public CursorPageResponse<AdminReportResponse> list(@Valid AdminReportSearchRequest request) {
		return adminReportService.search(request);
	}

	/** PATCH /admin/reports/{reportId} — ADMIN */
	@PatchMapping("/{reportId}")
	public ReportHandleResponse handle(@AuthUser AuthPrincipal principal, @PathVariable Long reportId,
			@Valid @RequestBody ReportHandleRequest request) {
		return adminReportService.handle(principal.userId(), reportId, request);
	}

}
