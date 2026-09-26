package com.reused.report.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.pagination.CursorPageRequest;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.report.dto.request.ReportCreateRequest;
import com.reused.report.dto.response.MyReportResponse;
import com.reused.report.dto.response.ReportCreateResponse;
import com.reused.report.service.ReportService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

	private final ReportService reportService;

	public ReportController(ReportService reportService) {
		this.reportService = reportService;
	}

	/** POST /reports — USER */
	@PostMapping
	public ResponseEntity<ReportCreateResponse> create(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody ReportCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(reportService.submit(principal.userId(), request));
	}

	/** GET /reports/me — USER */
	@GetMapping("/me")
	public CursorPageResponse<MyReportResponse> myReports(@AuthUser AuthPrincipal principal,
			@Valid CursorPageRequest page) {
		return reportService.findMyReports(principal.userId(), page);
	}

}
