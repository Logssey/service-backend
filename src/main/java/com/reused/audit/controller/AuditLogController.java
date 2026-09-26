package com.reused.audit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.audit.dto.request.AuditLogSearchRequest;
import com.reused.audit.dto.response.AuditLogResponse;
import com.reused.audit.service.AuditLogQueryService;
import com.reused.common.pagination.CursorPageResponse;

import jakarta.validation.Valid;

/**
 * 조회는 허용된 역할만 한다(NFR-LOG-004). 관리자 권한은 SecurityConfig의 URL 규칙과
 * {@code AdminAccessInterceptor}의 DB 재확인으로 이미 적용된다. 조회 자체는 감사 기록을 남기지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditLogController {

	private final AuditLogQueryService queryService;

	public AuditLogController(AuditLogQueryService queryService) {
		this.queryService = queryService;
	}

	/** GET /admin/audit-logs — ADMIN */
	@GetMapping
	public CursorPageResponse<AuditLogResponse> list(@Valid AuditLogSearchRequest request) {
		return queryService.search(request);
	}

}
