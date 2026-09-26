package com.reused.report.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.reused.report.api.ReportTarget;
import com.reused.report.api.ReportTargetResolver;
import com.reused.report.api.ReportTargetType;
import com.reused.user.api.UserQueryService;
import com.reused.user.api.UserSnapshot;

/**
 * 회원 신고 대상(B 소유). 소유자는 대상 자신이고 요약은 닉네임이다(0단계 계약 §5).
 *
 * <p>탈퇴 회원은 신고할 수 없다(없는 회원과 같은 404). 관리자 화면에서는 이미 접수된 신고를 보여야 하므로
 * {@link #describe}에는 탈퇴 회원도 담는다. 닉네임은 탈퇴 때 {@code 탈퇴회원#{userId}}로 바뀌어 있다.
 */
@Component
public class UserReportTargetResolver implements ReportTargetResolver {

	private final UserQueryService userQueryService;

	public UserReportTargetResolver(UserQueryService userQueryService) {
		this.userQueryService = userQueryService;
	}

	@Override
	public ReportTargetType type() {
		return ReportTargetType.USER;
	}

	@Override
	public Optional<ReportTarget> resolve(long targetId, long reporterId) {
		return userQueryService.findSnapshot(targetId)
				.filter(snapshot -> !snapshot.withdrawn())
				.map(UserReportTargetResolver::toTarget);
	}

	@Override
	public Map<Long, ReportTarget> describe(Collection<Long> targetIds) {
		Map<Long, ReportTarget> targets = new HashMap<>();
		userQueryService.findSnapshots(targetIds).forEach((userId, snapshot) -> targets.put(userId, toTarget(snapshot)));
		return targets;
	}

	private static ReportTarget toTarget(UserSnapshot snapshot) {
		return new ReportTarget(snapshot.userId(), snapshot.userId(), snapshot.nickname());
	}

}
