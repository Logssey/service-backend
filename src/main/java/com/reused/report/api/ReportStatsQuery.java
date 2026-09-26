package com.reused.report.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 신고 집계(B 제공). A의 관리자 게시글 목록 reportCount, B의 판매자 프로필 reportFlag와 관리자 회원 목록이 쓴다.
 *
 * <p>맵을 돌려주는 메서드는 요청한 id마다 값을 넣는다. 신고가 없으면 0이다. null 원소와 빈 입력은 무시한다.
 */
public interface ReportStatsQuery {

	/** 대상별 신고 수. 모든 상태를 센다 */
	Map<Long, Long> countByTargets(ReportTargetType type, Collection<Long> targetIds);

	/**
	 * 회원을 대상으로 한 신고 중 {@code since} 이후(포함) 처리가 RESOLVED로 끝난 수.
	 * 판매자 프로필의 신고 누적 경고(최근 90일 3건 이상)에 쓴다.
	 */
	long countResolvedAgainstUserSince(Long userId, Instant since);

	/** 회원을 대상으로 한 신고 수. 모든 상태를 센다 */
	Map<Long, Long> countAgainstUsers(Collection<Long> userIds);

}
