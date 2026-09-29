package com.reused.report.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.pagination.CursorCodec;
import com.reused.common.pagination.CursorPageRequest;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.report.api.ReportTarget;
import com.reused.report.api.ReportTargetType;
import com.reused.report.dto.request.ReportCreateRequest;
import com.reused.report.dto.response.MyReportResponse;
import com.reused.report.dto.response.ReportCreateResponse;
import com.reused.report.entity.Report;
import com.reused.report.entity.ReportStatus;
import com.reused.report.repository.ReportRepository;
import com.reused.user.api.ActiveUserGuard;

/**
 * 회원의 신고 접수와 내 신고 목록. 관리자 처리는 {@link AdminReportService}다.
 *
 * <p>신고 접수는 감사 로그를 남기지 않는다. FR-LOG-002의 "주요 사용자 행위" 예시에 없고,
 * 접수 자체가 reports 행으로 남기 때문이다. 알림도 없다.
 */
@Service
public class ReportService {

	static final String DUPLICATE_MESSAGE = "이미 접수된 신고입니다.";

	private final ReportRepository reportRepository;
	private final ReportTargetRegistry targets;
	private final ActiveUserGuard activeUserGuard;

	public ReportService(ReportRepository reportRepository, ReportTargetRegistry targets,
			ActiveUserGuard activeUserGuard) {
		this.reportRepository = reportRepository;
		this.targets = targets;
		this.activeUserGuard = activeUserGuard;
	}

	/**
	 * 판정 순서가 오류 우선순위다. 신고자(없음·탈퇴 401, 정지 403) → 사유(400) → 대상(404) → 본인 대상(400) → 미처리 중복(409).
	 * 차단 관계와 무관하게 받는다(UC-08: 차단은 신고와 독립).
	 *
	 * @throws BusinessException 위 순서의 오류
	 */
	@Transactional
	public ReportCreateResponse submit(Long reporterId, ReportCreateRequest request) {
		activeUserGuard.requireActive(reporterId);
		ReportTargetType targetType = request.targetType();
		long targetId = request.targetId();
		if (!request.reasonCode().isAllowedFor(targetType)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "대상 유형에 허용되지 않는 신고 사유입니다.");
		}
		ReportTarget target = targets.resolve(targetType, targetId, reporterId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "신고 대상을 찾을 수 없습니다."));
		if (target.ownerUserId() == reporterId) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, targetType == ReportTargetType.USER
					? "본인은 신고할 수 없습니다."
					: "본인이 작성한 콘텐츠는 신고할 수 없습니다.");
		}
		if (reportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndReasonCodeAndStatusIn(reporterId,
				targetType, targetId, request.reasonCode(), ReportStatus.PENDING)) {
			throw new BusinessException(ErrorCode.CONFLICT, DUPLICATE_MESSAGE);
		}

		Report report;
		try {
			report = reportRepository.saveAndFlush(Report.receive(reporterId, targetType, targetId,
					request.reasonCode(), normalizeDetail(request.detail())));
		}
		catch (DataIntegrityViolationException e) {
			// 중복 검사와 INSERT 사이의 경쟁 조건. 최종 판정은 부분 UNIQUE 인덱스다.
			throw new BusinessException(ErrorCode.CONFLICT, DUPLICATE_MESSAGE, e);
		}
		return ReportCreateResponse.from(report);
	}

	/**
	 * 본인이 접수한 신고만 최신순(report_id 내림차순)으로. 정지 회원도 볼 수 있다(business-rules 정지 중 차단 목록에 없음).
	 *
	 * @throws BusinessException UNAUTHENTICATED 없음·탈퇴. INVALID_INPUT 커서 해석 불가
	 */
	@Transactional(readOnly = true)
	public CursorPageResponse<MyReportResponse> findMyReports(Long reporterId, CursorPageRequest page) {
		activeUserGuard.requireMember(reporterId);
		int size = page.sizeOrDefault();
		Long cursorId = CursorCodec.decodeId(page.cursor());
		List<Report> rows = reportRepository.findByReporterIdAndIdLessThanOrderByIdDesc(reporterId,
				cursorId == null ? Long.MAX_VALUE : cursorId, Limit.of(size + 1));
		return CursorPageResponse.of(rows, size, report -> CursorCodec.encodeId(report.getId()))
				.map(MyReportResponse::from);
	}

	/**
	 * 앞뒤 공백만 있는 상세 내용은 없는 것으로 저장한다.
	 */
	private static String normalizeDetail(String detail) {
		if (detail == null) {
			return null;
		}
		String stripped = detail.strip();
		return stripped.isEmpty() ? null : stripped;
	}

}
