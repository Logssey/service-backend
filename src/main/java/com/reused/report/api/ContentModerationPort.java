package com.reused.report.api;

/**
 * 신고 처리의 콘텐츠 조치(A 구현). B의 신고 처리 트랜잭션에 참여한다. 구현이 없으면 신고 처리는 503이다.
 *
 * <p>감사 기록은 B(신고 처리)가 조치 한 건으로 남긴다. 구현은 감사 로그를 쓰지 않는다.
 *
 * @return 실제로 상태가 바뀌었는가. 이미 숨김·삭제된 대상이면 false(멱등)
 */
public interface ContentModerationPort {

	boolean hideListing(long listingId, long adminId, String reason);

	boolean deleteListing(long listingId, long adminId, String reason);

	boolean hideCommunityPost(long postId, long adminId, String reason);

	/** 숨김으로 실제 바뀐 경우에만 게시글의 comment_count를 줄인다 */
	boolean hideCommunityComment(long commentId, long adminId, String reason);

}
