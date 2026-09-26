package com.reused.report.dto.response;

import java.time.Instant;

import com.reused.report.entity.Report;
import com.reused.report.entity.ReportStatus;

/**
 * @param handledAt 검토 중(IN_REVIEW)이면 null
 */
public record ReportHandleResponse(Long reportId, ReportStatus status, Instant handledAt) {

	public static ReportHandleResponse from(Report report) {
		return new ReportHandleResponse(report.getId(), report.getStatus(), report.getHandledAt());
	}

}
