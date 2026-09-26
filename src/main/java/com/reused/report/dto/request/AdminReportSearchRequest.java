package com.reused.report.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.reused.common.pagination.CursorPageRequest;
import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.ReportStatus;

/**
 * 관리자 신고 목록 검색 조건(쿼리 파라미터). 암묵적 {@code @ModelAttribute}로 받는다.
 * 모르는 status·targetType 값은 바인딩 실패(typeMismatch)로 400이다.
 *
 * @param status 선택
 * @param targetType 선택. 5종 모두 허용한다
 * @param cursor 이전 응답의 nextCursor
 * @param size 1~100, 생략하면 {@link CursorPageRequest#DEFAULT_SIZE}
 */
public record AdminReportSearchRequest(
		ReportStatus status,
		ReportTargetType targetType,
		String cursor,
		@Min(1) @Max(100) Integer size) {

	public int sizeOrDefault() {
		return size == null ? CursorPageRequest.DEFAULT_SIZE : size;
	}

}
