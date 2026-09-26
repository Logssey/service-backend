package com.reused.report.dto.response;

import java.time.Instant;

import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.Report;
import com.reused.report.entity.ReportReasonCode;
import com.reused.report.entity.ReportStatus;

/**
 * 내 신고 목록의 항목. 신고 본문(detail)은 싣지 않는다(내 신고 목록 조회 명세).
 *
 * @param resolution 처리 완료(RESOLVED·REJECTED) 전까지 null
 * @param handledAt 처리 완료 전까지 null
 */
public record MyReportResponse(
		Long reportId,
		ReportTargetType targetType,
		Long targetId,
		ReportReasonCode reasonCode,
		ReportStatus status,
		String resolution,
		Instant createdAt,
		Instant handledAt) {

	public static MyReportResponse from(Report report) {
		return new MyReportResponse(report.getId(), report.getTargetType(), report.getTargetId(),
				report.getReasonCode(), report.getStatus(), report.getResolution(), report.getCreatedAt(),
				report.getHandledAt());
	}

}
