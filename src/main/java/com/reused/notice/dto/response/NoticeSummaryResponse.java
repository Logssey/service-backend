package com.reused.notice.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.reused.notice.entity.Notice;

/**
 * 공지 목록 항목(공지사항 목록 조회 명세). 본문은 싣지 않는다.
 *
 * <p>{@code is} 접두사 boolean 컴포넌트는 Jackson이 {@code pinned}로 내보낼 수 있어 이름을 명시한다.
 */
public record NoticeSummaryResponse(
		Long noticeId,
		String title,
		@JsonProperty("isPinned") boolean isPinned,
		Instant createdAt) {

	public static NoticeSummaryResponse from(Notice notice) {
		return new NoticeSummaryResponse(notice.getId(), notice.getTitle(), notice.isPinned(), notice.getCreatedAt());
	}

}
