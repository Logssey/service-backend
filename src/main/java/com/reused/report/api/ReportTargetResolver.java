package com.reused.report.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 신고 대상 확인(B가 정의, 대상 도메인이 유형별로 구현). USER 구현은 B가 가진다.
 * 신고 서비스는 {@code List<ReportTargetResolver>}에서 {@link #type()}으로 고른다. 해당 유형의 구현이 없으면 404다.
 */
public interface ReportTargetResolver {

	ReportTargetType type();

	/**
	 * 신고 접수용. 대상이 없거나 신고자가 볼 수 없으면 empty → 404. MESSAGE는 채팅 참여자인지까지 확인한다.
	 */
	Optional<ReportTarget> resolve(long targetId, long reporterId);

	/**
	 * 관리자 목록·처리용. 삭제·숨김된 대상도 반환한다. 행이 없는 id는 맵에서 빠진다.
	 */
	Map<Long, ReportTarget> describe(Collection<Long> targetIds);

}
