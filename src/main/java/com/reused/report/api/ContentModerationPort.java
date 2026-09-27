package com.reused.report.api;

/**
 * 신고 처리의 콘텐츠 조치(A 구현). B의 신고 처리 트랜잭션에 참여한다. 구현이 없으면 신고 처리는 503이다.
 *
 * <p>감사 기록은 B(신고 처리)가 조치 한 건으로 남긴다. 구현은 감사 로그를 쓰지 않는다.
 *
 * <p>boolean 반환 메서드는 실제 상태 변경 여부를 돌려준다. 이미 숨김·삭제된 대상이면 false(멱등).
 * 상품 숨김은 관리자 복구에 필요한 이전 상태도 함께 돌려준다.
 */
public interface ContentModerationPort {

	/** Captured under the listing row lock for the existing admin restore endpoint. */
	record ListingHideResult(boolean changed, String beforeStatus) {
	}

	ListingHideResult hideListing(long listingId, long adminId, String reason);

	boolean deleteListing(long listingId, long adminId, String reason);

	boolean hideCommunityPost(long postId, long adminId, String reason);

	/** 숨김으로 실제 바뀐 경우에만 게시글의 comment_count를 줄인다 */
	boolean hideCommunityComment(long commentId, long adminId, String reason);

}
