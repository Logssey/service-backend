package com.reused.admin.api;

import java.util.Collection;
import java.util.Map;

/**
 * 판매자별 게시글 수(B가 정의, A가 구현). 관리자 회원 목록의 {@code listingCount}에 쓴다.
 * B는 listings 테이블을 직접 읽지 않는다. 구현이 없으면 모든 회원을 0으로 본다.
 *
 * <p>호출하는 쪽은 한 페이지(최대 100명) 단위로 부른다. 관리자 목록의 읽기 전용 트랜잭션 안에서 호출된다.
 */
public interface ListingStatsPort {

	/**
	 * 삭제되지 않은(deleted_at IS NULL) 게시글을 모든 상태(ON_SALE, RESERVED, COMPLETED, HIDDEN)로 센다.
	 *
	 * @return 판매자 id → 게시글 수. 게시글이 없는 id는 빠져도 된다(0으로 본다)
	 */
	Map<Long, Long> countBySellers(Collection<Long> sellerIds);

}
