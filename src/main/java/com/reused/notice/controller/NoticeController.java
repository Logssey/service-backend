package com.reused.notice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.pagination.CursorPageRequest;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.notice.dto.response.NoticeResponse;
import com.reused.notice.dto.response.NoticeSummaryResponse;
import com.reused.notice.service.NoticeQueryService;

import jakarta.validation.Valid;

/**
 * 공개 공지 조회. 토큰이 없거나 유효하면 같은 응답이다. 잘못되거나 만료된 Authorization 헤더는 JwtAuthenticationFilter가 401로 막는다.
 */
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

	private final NoticeQueryService noticeQueryService;

	public NoticeController(NoticeQueryService noticeQueryService) {
		this.noticeQueryService = noticeQueryService;
	}

	/** GET /notices?cursor=&size= — 인증 불필요 */
	@GetMapping
	public CursorPageResponse<NoticeSummaryResponse> list(@Valid CursorPageRequest page) {
		return noticeQueryService.getNotices(page);
	}

	/** GET /notices/{noticeId} — 인증 불필요 */
	@GetMapping("/{noticeId}")
	public NoticeResponse detail(@PathVariable Long noticeId) {
		return noticeQueryService.getNotice(noticeId);
	}

}
