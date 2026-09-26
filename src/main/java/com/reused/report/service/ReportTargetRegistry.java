package com.reused.report.service;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.reused.report.api.ReportTarget;
import com.reused.report.api.ReportTargetResolver;
import com.reused.report.api.ReportTargetType;

/**
 * 대상 유형별 {@link ReportTargetResolver}를 모은다. USER는 B의 {@link UserReportTargetResolver}이고,
 * 나머지는 대상 도메인(A, 커뮤니티 담당)이 구현한다.
 *
 * <p>구현이 없는 유형은 "대상을 확인할 수 없음"으로 본다. 접수는 404, 관리자 목록의 요약은 null이다(0단계 계약 원칙 4).
 */
@Component
public class ReportTargetRegistry {

	private final Map<ReportTargetType, ReportTargetResolver> resolvers;

	/**
	 * @throws IllegalStateException 같은 유형의 구현이 둘 이상. 어느 쪽을 쓸지 정할 수 없어 기동을 멈춘다
	 */
	public ReportTargetRegistry(List<ReportTargetResolver> resolvers) {
		Map<ReportTargetType, ReportTargetResolver> byType = new EnumMap<>(ReportTargetType.class);
		for (ReportTargetResolver resolver : resolvers) {
			ReportTargetResolver previous = byType.putIfAbsent(resolver.type(), resolver);
			if (previous != null) {
				throw new IllegalStateException("신고 대상 유형마다 구현은 하나여야 한다: " + resolver.type());
			}
		}
		this.resolvers = Collections.unmodifiableMap(byType);
	}

	/**
	 * 신고 접수용.
	 *
	 * @return 구현이 없거나, 대상이 없거나, 신고자가 볼 수 없으면 empty
	 */
	public Optional<ReportTarget> resolve(ReportTargetType type, long targetId, long reporterId) {
		ReportTargetResolver resolver = resolvers.get(type);
		if (resolver == null) {
			return Optional.empty();
		}
		return resolver.resolve(targetId, reporterId);
	}

	/**
	 * 관리자 목록·처리용. 삭제·숨김된 대상도 담긴다.
	 *
	 * @return 구현이 없으면 빈 맵. 행이 없는 id는 빠진다
	 */
	public Map<Long, ReportTarget> describe(ReportTargetType type, Collection<Long> targetIds) {
		ReportTargetResolver resolver = resolvers.get(type);
		if (resolver == null || targetIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, ReportTarget> described = resolver.describe(targetIds);
		return described == null ? Map.of() : described;
	}

}
