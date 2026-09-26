package com.reused.notice.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.pagination.CursorPageRequest;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.notice.dto.response.NoticeResponse;
import com.reused.notice.dto.response.NoticeSummaryResponse;
import com.reused.notice.entity.Notice;
import com.reused.notice.repository.NoticeRepository;

/**
 * 공개 공지 조회. 로그인 여부와 무관하게 같은 응답이다(사용자별 필드가 없다). 삭제된 공지는 목록·상세 모두에서 즉시 빠진다.
 */
@Service
public class NoticeQueryService {

	private final NoticeRepository noticeRepository;

	public NoticeQueryService(NoticeRepository noticeRepository) {
		this.noticeRepository = noticeRepository;
	}

	/**
	 * 고정 공지 우선, 최신순. 페이지 사이에 고정 여부가 바뀌면 중복·누락이 생길 수 있다(ADR-013이 감수한 결과).
	 *
	 * @throws BusinessException INVALID_INPUT 해석할 수 없는 커서
	 */
	@Transactional(readOnly = true)
	public CursorPageResponse<NoticeSummaryResponse> getNotices(CursorPageRequest page) {
		int size = page.sizeOrDefault();
		NoticeCursor cursor = NoticeCursor.decode(page.cursor());
		List<Notice> rows = cursor == null
				? noticeRepository.findFirstPage(size + 1)
				: noticeRepository.findPageAfter(cursor.pinned(), cursor.createdAt(), cursor.noticeId(), size + 1);
		return CursorPageResponse.of(rows, size, NoticeCursor::encode).map(NoticeSummaryResponse::from);
	}

	/**
	 * @throws BusinessException NOT_FOUND 없거나 삭제된 공지. 두 경우를 구분하지 않는다
	 */
	@Transactional(readOnly = true)
	public NoticeResponse getNotice(Long noticeId) {
		return noticeRepository.findByIdAndDeletedAtIsNull(noticeId)
				.map(NoticeResponse::from)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다."));
	}

}
