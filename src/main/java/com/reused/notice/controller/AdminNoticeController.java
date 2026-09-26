package com.reused.notice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.notice.dto.request.NoticeCreateRequest;
import com.reused.notice.dto.request.NoticeUpdateRequest;
import com.reused.notice.dto.response.NoticeCreateResponse;
import com.reused.notice.dto.response.NoticeResponse;
import com.reused.notice.service.AdminNoticeService;

import jakarta.validation.Valid;

/**
 * 관리자 공지 관리. 권한은 SecurityConfig({@code /api/v1/admin/**} → ADMIN)와 AdminAccessInterceptor(DB 재확인)가 건다.
 */
@RestController
@RequestMapping("/api/v1/admin/notices")
public class AdminNoticeController {

	private final AdminNoticeService adminNoticeService;

	public AdminNoticeController(AdminNoticeService adminNoticeService) {
		this.adminNoticeService = adminNoticeService;
	}

	/** POST /admin/notices — ADMIN */
	@PostMapping
	public ResponseEntity<NoticeCreateResponse> create(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody NoticeCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(adminNoticeService.create(principal.userId(), request));
	}

	/** PATCH /admin/notices/{noticeId} — ADMIN */
	@PatchMapping("/{noticeId}")
	public NoticeResponse update(@AuthUser AuthPrincipal principal, @PathVariable Long noticeId,
			@Valid @RequestBody NoticeUpdateRequest request) {
		return adminNoticeService.update(principal.userId(), noticeId, request);
	}

	/** DELETE /admin/notices/{noticeId} — ADMIN */
	@DeleteMapping("/{noticeId}")
	public ResponseEntity<Void> delete(@AuthUser AuthPrincipal principal, @PathVariable Long noticeId) {
		adminNoticeService.delete(principal.userId(), noticeId);
		return ResponseEntity.noContent().build();
	}

}
