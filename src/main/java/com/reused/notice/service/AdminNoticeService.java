package com.reused.notice.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditTargetType;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.notice.dto.request.NoticeCreateRequest;
import com.reused.notice.dto.request.NoticeUpdateRequest;
import com.reused.notice.dto.response.NoticeCreateResponse;
import com.reused.notice.dto.response.NoticeResponse;
import com.reused.notice.entity.Notice;
import com.reused.notice.repository.NoticeRepository;
import com.reused.notification.api.NotificationEventPublisher;

/**
 * 관리자 공지 등록·수정·삭제(FR-ADMIN-011).
 *
 * <p>관리자 여부(토큰 역할, DB 역할·탈퇴·정지)는 URL 규칙과 {@code AdminAccessInterceptor}가 이미 확인했다.
 * 여기서 다시 확인하지 않는다.
 *
 * <p>변경과 감사 기록은 한 트랜잭션이다(FR-LOG-004, NFR-AUTH-013). 감사 기록이 실패하면 변경도 롤백된다.
 * 감사 detail에 제목·본문을 넣지 않는다(NFR-LOG-003). 수정은 요청에 담긴 필드 이름만 남긴다.
 */
@Service
public class AdminNoticeService {

	private static final String NOT_FOUND_MESSAGE = "공지사항을 찾을 수 없습니다.";

	private final NoticeRepository noticeRepository;
	private final AuditLogger auditLogger;
	private final NotificationEventPublisher notificationEventPublisher;

	public AdminNoticeService(NoticeRepository noticeRepository, AuditLogger auditLogger,
			NotificationEventPublisher notificationEventPublisher) {
		this.noticeRepository = noticeRepository;
		this.auditLogger = auditLogger;
		this.notificationEventPublisher = notificationEventPublisher;
	}

	/**
	 * 등록하면 전체 회원에게 NOTICE_PUBLISHED를 보낸다(공지사항 등록 명세). 발송은 커밋 뒤 별도 트랜잭션이라
	 * 실패해도 등록은 유지된다(ADR-014). 탈퇴자와 공지 알림을 끈 회원은 받지 않는다.
	 */
	@Transactional
	public NoticeCreateResponse create(Long adminId, NoticeCreateRequest request) {
		Notice notice = noticeRepository.save(Notice.publish(adminId, request.title(), request.content(),
				request.pinnedOrDefault(), now()));
		auditLogger.record(AuditEntry.success(AuditAction.NOTICE_CREATE, adminId, AuditTargetType.NOTICE,
				notice.getId(), null));
		notificationEventPublisher.publishNoticeToAll(notice.getId(), notice.getTitle());
		return new NoticeCreateResponse(notice.getId());
	}

	/**
	 * 담긴 필드만 바꾼다. 알림은 보내지 않는다(명세는 등록 시에만 발송). 동시 수정은 마지막 쓰기가 이긴다.
	 *
	 * @throws BusinessException INVALID_INPUT 바꿀 필드가 하나도 없음. NOT_FOUND 없거나 삭제된 공지
	 */
	@Transactional
	public NoticeResponse update(Long adminId, Long noticeId, NoticeUpdateRequest request) {
		List<String> changedFields = request.changedFields();
		if (changedFields.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "변경할 항목이 없습니다.");
		}
		Notice notice = lockActive(noticeId);
		notice.edit(request.title(), request.content(), request.isPinned(), now());
		auditLogger.record(AuditEntry.success(AuditAction.NOTICE_UPDATE, adminId, AuditTargetType.NOTICE,
				noticeId, Map.of("changedFields", changedFields)));
		return NoticeResponse.from(notice);
	}

	/**
	 * 논리 삭제. 이미 삭제된 공지는 없는 공지와 같이 404다. 이미 보낸 알림은 지우지 않는다(누르면 상세가 404).
	 *
	 * @throws BusinessException NOT_FOUND 없거나 이미 삭제된 공지
	 */
	@Transactional
	public void delete(Long adminId, Long noticeId) {
		Notice notice = lockActive(noticeId);
		notice.delete(now());
		auditLogger.record(AuditEntry.success(AuditAction.NOTICE_DELETE, adminId, AuditTargetType.NOTICE,
				noticeId, null));
	}

	private Notice lockActive(Long noticeId) {
		return noticeRepository.findActiveByIdForUpdate(noticeId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE));
	}

	/**
	 * PostgreSQL TIMESTAMPTZ는 마이크로초 정밀도다. 자르지 않으면 수정 응답의 updatedAt(나노초)과 이후 조회 값이 다르다.
	 */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
