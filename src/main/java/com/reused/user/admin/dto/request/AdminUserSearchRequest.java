package com.reused.user.admin.dto.request;

import com.reused.common.pagination.CursorPageRequest;
import com.reused.user.entity.UserStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 회원 목록 검색 조건(쿼리 파라미터). 암묵적 {@code @ModelAttribute}로 받는다.
 * status 값이 enum이 아니면 바인딩 오류(typeMismatch)로 400이다.
 *
 * @param status 없으면 전체
 * @param keyword 닉네임 부분 일치(대소문자 무시). 앞뒤 공백을 떼고 비어 있으면 적용하지 않는다.
 *        닉네임 최대 길이(VARCHAR(20))를 넘으면 400
 * @param cursor 이전 응답의 nextCursor
 * @param size 기본 {@link CursorPageRequest#DEFAULT_SIZE}, 1~100 밖이면 400
 */
public record AdminUserSearchRequest(
		UserStatus status,
		@Size(max = 20) String keyword,
		String cursor,
		@Min(1) @Max(100) Integer size) {

	public int sizeOrDefault() {
		return size == null ? CursorPageRequest.DEFAULT_SIZE : size;
	}

	/** @return 공백을 뗀 검색어. 비어 있으면 null */
	public String normalizedKeyword() {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}
		return keyword.strip();
	}

}
