package com.reused.report;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.reused.report.api.ContentModerationPort;

/**
 * A의 {@link ContentModerationPort} 대역. 호출을 기록하고, 같은 대상에 대한 두 번째 조치는 바뀐 것이 없다(false)고 답한다.
 * 호출 시점에 트랜잭션이 열려 있었는지도 남겨, 포트가 신고 처리 트랜잭션 안에서 불리는지 확인한다.
 */
public class RecordingContentModerationPort implements ContentModerationPort {

	public record Call(String method, long targetId, long adminId, String reason, boolean inTransaction) {
	}

	private final List<Call> calls = new CopyOnWriteArrayList<>();
	private final Set<String> applied = ConcurrentHashMap.newKeySet();
	private volatile RuntimeException failure;

	@Override
	public ListingHideResult hideListing(long listingId, long adminId, String reason) {
		boolean changed = apply("hideListing", listingId, adminId, reason);
		return new ListingHideResult(changed, changed ? "ON_SALE" : null);
	}

	@Override
	public boolean deleteListing(long listingId, long adminId, String reason) {
		return apply("deleteListing", listingId, adminId, reason);
	}

	@Override
	public boolean hideCommunityPost(long postId, long adminId, String reason) {
		return apply("hideCommunityPost", postId, adminId, reason);
	}

	@Override
	public boolean hideCommunityComment(long commentId, long adminId, String reason) {
		return apply("hideCommunityComment", commentId, adminId, reason);
	}

	/** 다음 호출부터 이 예외를 던진다 */
	public void failWith(RuntimeException failure) {
		this.failure = failure;
	}

	public List<Call> calls() {
		return List.copyOf(calls);
	}

	/** 실제로 상태를 바꾼 조치 수. 댓글이면 commentCount가 줄어든 횟수다 */
	public int appliedCount() {
		return applied.size();
	}

	public void reset() {
		calls.clear();
		applied.clear();
		failure = null;
	}

	private boolean apply(String method, long targetId, long adminId, String reason) {
		calls.add(new Call(method, targetId, adminId, reason,
				TransactionSynchronizationManager.isActualTransactionActive()));
		if (failure != null) {
			throw failure;
		}
		return applied.add(method + ":" + targetId);
	}

}
