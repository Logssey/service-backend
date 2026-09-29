package com.reused.report.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.reused.report.entity.ReportAction;
import com.reused.report.entity.ReportStatus;

/**
 * 신고 처리(신고 처리 명세). 상태에 따라 달라지는 규칙(resolution 필수, 조치 허용)은 서비스가 검증한다.
 *
 * @param status IN_REVIEW | RESOLVED | REJECTED. RECEIVED는 400
 * @param resolution RESOLVED·REJECTED일 때 필수. IN_REVIEW에서는 저장하지 않는다
 * @param action 선택. 생략하면 NONE. RESOLVED에서만 NONE이 아닌 값을 쓸 수 있다
 */
public record ReportHandleRequest(
		@NotNull ReportStatus status,
		@Size(max = 500) String resolution,
		ReportAction action) {
}
