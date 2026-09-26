package com.reused.notice.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.reused.notice.entity.Notice;

/**
 * 공지 상세(공지사항 상세 조회 명세). 수정 응답도 이 형식이다.
 *
 * <p>작성자와 삭제 시각은 명세 DTO에 없어 싣지 않는다. 본문은 원문 그대로다. 프론트는 HTML로 해석하지 않고 텍스트로 그린다.
 *
 * @param updatedAt 한 번도 수정하지 않았으면 null
 */
public record NoticeResponse(
		Long noticeId,
		String title,
		String content,
		@JsonProperty("isPinned") boolean isPinned,
		Instant createdAt,
		Instant updatedAt) {

	public static NoticeResponse from(Notice notice) {
		return new NoticeResponse(notice.getId(), notice.getTitle(), notice.getContent(), notice.isPinned(),
				notice.getCreatedAt(), notice.getUpdatedAt());
	}

}
