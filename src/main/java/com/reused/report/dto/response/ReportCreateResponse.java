package com.reused.report.dto.response;

import com.reused.report.entity.Report;
import com.reused.report.entity.ReportStatus;

/**
 * @param status 접수 직후라 항상 RECEIVED
 */
public record ReportCreateResponse(Long reportId, ReportStatus status) {

	public static ReportCreateResponse from(Report report) {
		return new ReportCreateResponse(report.getId(), report.getStatus());
	}

}
