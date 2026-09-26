package com.reused.report.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditTargetType;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.pagination.CursorCodec;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.notification.api.NotificationEvent;
import com.reused.notification.api.NotificationEventPublisher;
import com.reused.notification.api.NotificationTargetType;
import com.reused.notification.api.NotificationType;
import com.reused.report.api.ContentModerationPort;
import com.reused.report.api.ReportTarget;
import com.reused.report.api.ReportTargetType;
import com.reused.report.dto.request.AdminReportSearchRequest;
import com.reused.report.dto.request.ReportHandleRequest;
import com.reused.report.dto.response.AdminReportResponse;
import com.reused.report.dto.response.ReportHandleResponse;
import com.reused.report.entity.Report;
import com.reused.report.entity.ReportAction;
import com.reused.report.entity.ReportStatus;
import com.reused.report.repository.ReportRepository;
import com.reused.report.repository.ReportSearchRepository;
import com.reused.user.api.UserQueryService;
import com.reused.user.api.UserSnapshot;
import com.reused.user.dto.response.UserSummaryResponse;
import com.reused.user.service.UserModerationService;

/**
 * 관리자 신고 목록과 신고 처리(FR-ADMIN-006~008). 관리자 권한은 URL 규칙과 {@code AdminAccessInterceptor}가
 * 이미 확인했으므로 여기서 다시 확인하지 않는다.
 *
 * <p>신고 처리는 상태 변경, 조치(콘텐츠 숨김·삭제 또는 이용정지), 감사 기록이 한 트랜잭션이다. 어느 하나가 실패하면
 * 모두 롤백된다. 신고자 알림은 커밋된 경우에만 저장되고, 저장 실패는 처리를 되돌리지 않는다(ADR-014).
 */
@Service
public class AdminReportService {

	/** 메시지·댓글의 targetSummary는 "내용 일부"다(신고 목록 조회 명세). 이 길이(문자 수)를 넘으면 자른다. */
	static final int CONTENT_SUMMARY_LENGTH = 50;
	private static final String ELLIPSIS = "…";

	private final ReportRepository reportRepository;
	private final ReportSearchRepository searchRepository;
	private final ReportTargetRegistry targets;
	private final UserQueryService userQueryService;
	private final UserModerationService moderationService;
	private final ObjectProvider<ContentModerationPort> contentModeration;
	private final AuditLogger auditLogger;
	private final NotificationEventPublisher notificationPublisher;

	public AdminReportService(ReportRepository reportRepository, ReportSearchRepository searchRepository,
			ReportTargetRegistry targets, UserQueryService userQueryService, UserModerationService moderationService,
			ObjectProvider<ContentModerationPort> contentModeration, AuditLogger auditLogger,
			NotificationEventPublisher notificationPublisher) {
		this.reportRepository = reportRepository;
		this.searchRepository = searchRepository;
		this.targets = targets;
		this.userQueryService = userQueryService;
		this.moderationService = moderationService;
		this.contentModeration = contentModeration;
		this.auditLogger = auditLogger;
		this.notificationPublisher = notificationPublisher;
	}

	/**
	 * report_id 내림차순. 신고자와 대상 요약은 페이지 단위로 한 번씩 읽는다(유형별 describe 한 번).
	 *
	 * @throws BusinessException INVALID_INPUT 커서 해석 불가
	 */
	@Transactional(readOnly = true)
	public CursorPageResponse<AdminReportResponse> search(AdminReportSearchRequest request) {
		int size = request.sizeOrDefault();
		Long cursorId = CursorCodec.decodeId(request.cursor());
		List<Report> rows = searchRepository.search(request.status(), request.targetType(), cursorId, size + 1);
		CursorPageResponse<Report> page = CursorPageResponse.of(rows, size,
				report -> CursorCodec.encodeId(report.getId()));

		Map<Long, UserSnapshot> reporters = userQueryService.findSnapshots(
				page.items().stream().map(Report::getReporterId).toList());
		Map<ReportTargetType, Map<Long, ReportTarget>> described = describeTargets(page.items());
		return page.map(report -> AdminReportResponse.of(report, reporterOf(reporters, report.getReporterId()),
				targetSummaryOf(described, report)));
	}

	/**
	 * 신고 처리. 요청 자체의 오류(400)를 먼저 보고, 그다음 신고(404)와 상태(409), 대상과 조치의 짝(400) 순서다.
	 *
	 * <ul>
	 *   <li>IN_REVIEW: 검토를 시작한 관리자만 남긴다. 이미 검토 중이면 아무것도 하지 않는다. 알림 없음
	 *   <li>RESOLVED: 조치를 실행하고 결과를 남긴 뒤 신고자에게 알린다
	 *   <li>REJECTED: 조치 없이 결과를 남기고 신고자에게 알린다
	 * </ul>
	 *
	 * @throws BusinessException INVALID_INPUT 요청 오류·조치 불일치, NOT_FOUND 신고 없음·정지 대상 확인 불가,
	 *         CONFLICT 이미 최종 상태, SERVICE_UNAVAILABLE 콘텐츠 조치 구현 없음. 이용정지의 오류(403 본인, 409 탈퇴)는
	 *         {@link UserModerationService#suspendForReport}의 것이다
	 */
	@Transactional
	public ReportHandleResponse handle(Long adminId, Long reportId, ReportHandleRequest request) {
		ReportStatus next = request.status();
		ReportAction action = request.action() == null ? ReportAction.NONE : request.action();
		validateRequest(next, request.resolution(), action);

		Report report = reportRepository.findByIdForUpdate(reportId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));
		if (report.getStatus().isClosed()) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 처리가 끝난 신고입니다.");
		}
		if (!action.isAllowedFor(report.getTargetType())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "신고 대상에 맞지 않는 조치입니다.");
		}

		ReportStatus before = report.getStatus();
		if (next == ReportStatus.IN_REVIEW) {
			if (before != ReportStatus.IN_REVIEW) {
				report.startReview(adminId);
				recordHandle(adminId, report, before, action);
			}
			return ReportHandleResponse.from(report);
		}

		// DB(TIMESTAMPTZ)가 마이크로초까지라 응답과 저장값이 같도록 맞춘다.
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		String resolution = request.resolution().strip();
		if (next == ReportStatus.RESOLVED) {
			applyAction(adminId, report, action, resolution);
			report.resolve(adminId, resolution, now);
		}
		else {
			report.reject(adminId, resolution, now);
		}
		recordHandle(adminId, report, before, action);
		notifyReporter(report, action);
		return ReportHandleResponse.from(report);
	}

	/**
	 * 상태만 보고 판정할 수 있는 요청 오류. resolution 누락은 신고 처리 명세의 400이다.
	 */
	private static void validateRequest(ReportStatus next, String resolution, ReportAction action) {
		if (next == ReportStatus.RECEIVED) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "접수(RECEIVED) 상태로는 바꿀 수 없습니다.");
		}
		if (next.isClosed() && (resolution == null || resolution.isBlank())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "처리 결과(resolution)를 입력해 주세요.");
		}
		if (action != ReportAction.NONE && next != ReportStatus.RESOLVED) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "조치는 처리 완료(RESOLVED)에서만 지정할 수 있습니다.");
		}
	}

	private void applyAction(Long adminId, Report report, ReportAction action, String reason) {
		switch (action) {
			case NONE -> {
			}
			case SUSPEND_USER -> suspendOwner(adminId, report, reason);
			default -> moderateContent(adminId, report, action, reason);
		}
	}

	/**
	 * 대상의 작성자·소유자를 기본 기간 정지한다. 이력·감사(USER_SUSPEND)·토큰 폐기는 {@link UserModerationService}가 한다.
	 * 대상 도메인 구현이 없거나 대상 행이 없으면 누구를 정지할지 알 수 없어 404다.
	 */
	private void suspendOwner(Long adminId, Report report, String reason) {
		ReportTarget target = targets.describe(report.getTargetType(), List.of(report.getTargetId()))
				.get(report.getTargetId());
		if (target == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "정지할 회원을 찾을 수 없습니다.");
		}
		moderationService.suspendForReport(adminId, target.ownerUserId(), reason, report.getId());
	}

	/**
	 * 콘텐츠 조치는 대상 도메인(A)의 포트가 이 트랜잭션 안에서 한다. 감사는 여기서 조치 한 건으로 남긴다(0단계 계약 §5).
	 * 포트가 이미 숨김·삭제된 대상이라 바뀐 것이 없다고 해도 관리자 조치 자체는 기록한다({@code changed=false}).
	 */
	private void moderateContent(Long adminId, Report report, ReportAction action, String reason) {
		ContentModerationPort port = contentModeration.getIfAvailable();
		if (port == null) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "지금은 콘텐츠 조치를 수행할 수 없습니다.");
		}
		long targetId = report.getTargetId();
		long admin = adminId;
		boolean changed = switch (action) {
			case HIDE_LISTING -> port.hideListing(targetId, admin, reason);
			case DELETE_LISTING -> port.deleteListing(targetId, admin, reason);
			case HIDE_COMMUNITY_POST -> port.hideCommunityPost(targetId, admin, reason);
			case HIDE_COMMUNITY_COMMENT -> port.hideCommunityComment(targetId, admin, reason);
			default -> throw new IllegalStateException("콘텐츠 조치가 아니다: " + action);
		};

		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("reportId", report.getId());
		detail.put("reason", reason);
		detail.put("changed", changed);
		auditLogger.record(AuditEntry.success(contentAuditAction(action), adminId, contentAuditTarget(action),
				targetId, detail));
	}

	/**
	 * 신고 본문(detail)과 처리 결과(resolution)는 reports에 있으므로 감사 detail에는 상태와 조치만 남긴다.
	 */
	private void recordHandle(Long adminId, Report report, ReportStatus before, ReportAction action) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("before", before.name());
		detail.put("after", report.getStatus().name());
		detail.put("action", action.name());
		detail.put("targetType", report.getTargetType().name());
		detail.put("targetId", report.getTargetId());
		auditLogger.record(AuditEntry.success(AuditAction.REPORT_HANDLE, adminId, AuditTargetType.REPORT,
				report.getId(), detail));
	}

	/**
	 * 처리 완료(RESOLVED·REJECTED) 알림. 본문은 조치 요약이고 resolution 원문은 넣지 않는다(와이어프레임 NOTI-001).
	 * 알림을 누르면 내 신고 내역으로 간다(target REPORT).
	 */
	private void notifyReporter(Report report, ReportAction action) {
		notificationPublisher.publish(new NotificationEvent(report.getReporterId(), NotificationType.REPORT_RESOLVED,
				resultSummary(report.getStatus(), action), NotificationTargetType.REPORT, report.getId()));
	}

	static String resultSummary(ReportStatus status, ReportAction action) {
		if (status == ReportStatus.REJECTED) {
			return "신고가 반려되었습니다";
		}
		return switch (action) {
			case HIDE_LISTING, HIDE_COMMUNITY_POST -> "게시글 숨김 조치";
			case DELETE_LISTING -> "게시글 삭제 조치";
			case HIDE_COMMUNITY_COMMENT -> "댓글 숨김 조치";
			case SUSPEND_USER -> "이용정지 조치";
			case NONE -> "신고가 처리되었습니다";
		};
	}

	private static AuditAction contentAuditAction(ReportAction action) {
		return switch (action) {
			case HIDE_LISTING -> AuditAction.ADMIN_LISTING_HIDE;
			case DELETE_LISTING -> AuditAction.ADMIN_LISTING_DELETE;
			case HIDE_COMMUNITY_POST -> AuditAction.COMMUNITY_POST_HIDE;
			case HIDE_COMMUNITY_COMMENT -> AuditAction.COMMUNITY_COMMENT_HIDE;
			default -> throw new IllegalStateException("콘텐츠 조치가 아니다: " + action);
		};
	}

	private static AuditTargetType contentAuditTarget(ReportAction action) {
		return switch (action) {
			case HIDE_LISTING, DELETE_LISTING -> AuditTargetType.LISTING;
			case HIDE_COMMUNITY_POST -> AuditTargetType.COMMUNITY_POST;
			case HIDE_COMMUNITY_COMMENT -> AuditTargetType.COMMUNITY_COMMENT;
			default -> throw new IllegalStateException("콘텐츠 조치가 아니다: " + action);
		};
	}

	private Map<ReportTargetType, Map<Long, ReportTarget>> describeTargets(List<Report> reports) {
		Map<ReportTargetType, Set<Long>> idsByType = reports.stream().collect(Collectors.groupingBy(
				Report::getTargetType, () -> new EnumMap<>(ReportTargetType.class),
				Collectors.mapping(Report::getTargetId, Collectors.toSet())));
		Map<ReportTargetType, Map<Long, ReportTarget>> described = new EnumMap<>(ReportTargetType.class);
		idsByType.forEach((type, ids) -> described.put(type, targets.describe(type, ids)));
		return described;
	}

	/**
	 * reporter_id는 users FK(RESTRICT)라 행이 늘 있다. 그래도 없으면 id만 담는다.
	 */
	private static UserSummaryResponse reporterOf(Map<Long, UserSnapshot> reporters, Long reporterId) {
		UserSnapshot snapshot = reporters.get(reporterId);
		return snapshot == null ? new UserSummaryResponse(reporterId, null, null) : snapshot.toSummary();
	}

	private static String targetSummaryOf(Map<ReportTargetType, Map<Long, ReportTarget>> described, Report report) {
		ReportTarget target = described.getOrDefault(report.getTargetType(), Map.of()).get(report.getTargetId());
		if (target == null || target.summary() == null) {
			return null;
		}
		return switch (report.getTargetType()) {
			case MESSAGE, COMMUNITY_COMMENT -> excerpt(target.summary());
			case LISTING, USER, COMMUNITY_POST -> target.summary();
		};
	}

	/**
	 * 문자(코드 포인트) 단위로 자른다. 서로게이트 쌍 가운데를 자르지 않는다.
	 */
	static String excerpt(String content) {
		if (content.codePointCount(0, content.length()) <= CONTENT_SUMMARY_LENGTH) {
			return content;
		}
		return content.substring(0, content.offsetByCodePoints(0, CONTENT_SUMMARY_LENGTH)) + ELLIPSIS;
	}

}
