package com.reused.report.entity;

import java.util.Set;

/**
 * 신고 처리 상태. DB CHECK 제약(ck_reports_status)과 값이 같아야 한다.
 *
 * <p>RECEIVED → IN_REVIEW → RESOLVED | REJECTED. RECEIVED에서 바로 RESOLVED·REJECTED로 갈 수도 있다.
 * RESOLVED·REJECTED는 최종 상태라 다시 바꿀 수 없다(신고 처리 명세 409, 와이어프레임 ADM-003).
 */
public enum ReportStatus {
	RECEIVED,
	IN_REVIEW,
	RESOLVED,
	REJECTED;

	/** 미처리 상태. 중복 신고를 막는 부분 UNIQUE 인덱스(uq_reports_pending_duplicate)의 조건과 같다. */
	public static final Set<ReportStatus> PENDING = Set.of(RECEIVED, IN_REVIEW);

	public boolean isClosed() {
		return this == RESOLVED || this == REJECTED;
	}

}
