package com.reused.report.dto.response;

import java.time.Instant;

import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.Report;
import com.reused.report.entity.ReportReasonCode;
import com.reused.report.entity.ReportStatus;
import com.reused.user.dto.response.UserSummaryResponse;

/**
 * 관리자 신고 목록의 항목(신고 목록 조회 명세).
 *
 * @param reporter 탈퇴한 신고자는 {@code 탈퇴회원#{userId}}로 보인다
 * @param targetSummary 게시글 제목, 닉네임, 메시지·댓글 내용 일부. 대상을 확인할 수 없으면 null
 * @param handledBy 검토를 시작했거나 처리한 관리자의 userId. 문서 예시가 null뿐이라 컬럼 그대로 id로 둔다
 */
public record AdminReportResponse(
		Long reportId,
		UserSummaryResponse reporter,
		ReportTargetType targetType,
		Long targetId,
		String targetSummary,
		ReportReasonCode reasonCode,
		String detail,
		ReportStatus status,
		Long handledBy,
		String resolution,
		Instant createdAt) {

	public static AdminReportResponse of(Report report, UserSummaryResponse reporter, String targetSummary) {
		return new AdminReportResponse(report.getId(), reporter, report.getTargetType(), report.getTargetId(),
				targetSummary, report.getReasonCode(), report.getDetail(), report.getStatus(), report.getHandledBy(),
				report.getResolution(), report.getCreatedAt());
	}

}
