package com.reused.report.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.ReportReasonCode;

/**
 * 신고 접수(신고 접수 명세). 모르는 enum 문자열은 본문 역직렬화 실패로 400이다.
 *
 * @param detail 선택. 앞뒤 공백을 떼고 비면 저장하지 않는다
 */
public record ReportCreateRequest(
		@NotNull ReportTargetType targetType,
		@NotNull Long targetId,
		@NotNull ReportReasonCode reasonCode,
		@Size(max = 500) String detail) {
}
