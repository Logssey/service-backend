package com.reused.user.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 회원 요약 조회(판매자·구매자·상대방·작성자 표시). 목록 응답은 {@link #findSnapshots}로 한 번에 읽어 N+1을 피한다.
 * 탈퇴 회원도 조회된다. 표시 방식은 호출하는 쪽이 정한다.
 */
public interface UserQueryService {

	Optional<UserSnapshot> findSnapshot(Long userId);

	/**
	 * @return 없는 id는 맵에서 빠진다. null 원소와 빈 입력은 무시한다
	 */
	Map<Long, UserSnapshot> findSnapshots(Collection<Long> userIds);

}
