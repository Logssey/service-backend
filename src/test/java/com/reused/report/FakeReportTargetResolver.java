package com.reused.report;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.reused.report.api.ReportTarget;
import com.reused.report.api.ReportTargetResolver;
import com.reused.report.api.ReportTargetType;

/**
 * 대상 도메인(A, 커뮤니티 담당)의 {@link ReportTargetResolver} 대역. 대상을 메모리에 둔다.
 *
 * <p>계약대로 동작한다. 숨김·삭제된 대상은 접수(resolve)에서는 보이지 않고 관리자 목록(describe)에는 보인다.
 * 볼 수 있는 신고자를 정한 대상(채팅 메시지)은 그 밖의 신고자에게 없는 것처럼 보인다.
 */
public class FakeReportTargetResolver implements ReportTargetResolver {

	private final ReportTargetType type;
	private final Map<Long, ReportTarget> targets = new ConcurrentHashMap<>();
	private final Set<Long> removed = ConcurrentHashMap.newKeySet();
	private final Map<Long, Set<Long>> audiences = new ConcurrentHashMap<>();

	public FakeReportTargetResolver(ReportTargetType type) {
		this.type = type;
	}

	public void add(long targetId, long ownerUserId, String summary) {
		targets.put(targetId, new ReportTarget(targetId, ownerUserId, summary));
	}

	/** 채팅 참여자처럼 정해진 신고자만 볼 수 있는 대상 */
	public void addVisibleTo(long targetId, long ownerUserId, String summary, Set<Long> reporters) {
		add(targetId, ownerUserId, summary);
		audiences.put(targetId, Set.copyOf(reporters));
	}

	/** 숨김·삭제. 새로 신고할 수 없지만 관리자는 계속 본다 */
	public void remove(long targetId) {
		removed.add(targetId);
	}

	public void clear() {
		targets.clear();
		removed.clear();
		audiences.clear();
	}

	@Override
	public ReportTargetType type() {
		return type;
	}

	@Override
	public Optional<ReportTarget> resolve(long targetId, long reporterId) {
		if (removed.contains(targetId)) {
			return Optional.empty();
		}
		Set<Long> audience = audiences.get(targetId);
		if (audience != null && !audience.contains(reporterId)) {
			return Optional.empty();
		}
		return Optional.ofNullable(targets.get(targetId));
	}

	@Override
	public Map<Long, ReportTarget> describe(Collection<Long> targetIds) {
		Map<Long, ReportTarget> described = new HashMap<>();
		for (Long targetId : targetIds) {
			ReportTarget target = targets.get(targetId);
			if (target != null) {
				described.put(targetId, target);
			}
		}
		return described;
	}

}
