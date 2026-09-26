package com.reused.report.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.reused.report.api.ReportTargetType;

/**
 * 신고 처리와 함께 수행하는 조치(신고 처리 명세의 조치 코드). reports에 저장하는 컬럼은 없다.
 * 무엇을 했는지는 감사 로그(REPORT_HANDLE의 detail과 조치 한 건)에 남는다.
 *
 * <p>대상과 맞는 조치만 허용한다. 커뮤니티 조치는 대상과 일치하는 숨김만 허용한다(신고 처리 명세).
 * SUSPEND_USER는 모든 유형에 허용하며 대상의 작성자·소유자를 정지한다(0단계 계약 §5).
 * 메시지를 숨기는 조치는 문서에 없다.
 */
public enum ReportAction {
	HIDE_LISTING,
	DELETE_LISTING,
	HIDE_COMMUNITY_POST,
	HIDE_COMMUNITY_COMMENT,
	SUSPEND_USER,
	NONE;

	private static final Map<ReportTargetType, Set<ReportAction>> ALLOWED = allowedByTargetType();

	public boolean isAllowedFor(ReportTargetType targetType) {
		return ALLOWED.get(targetType).contains(this);
	}

	private static Map<ReportTargetType, Set<ReportAction>> allowedByTargetType() {
		Map<ReportTargetType, Set<ReportAction>> allowed = new EnumMap<>(ReportTargetType.class);
		allowed.put(ReportTargetType.LISTING, EnumSet.of(HIDE_LISTING, DELETE_LISTING, SUSPEND_USER, NONE));
		allowed.put(ReportTargetType.USER, EnumSet.of(SUSPEND_USER, NONE));
		allowed.put(ReportTargetType.MESSAGE, EnumSet.of(SUSPEND_USER, NONE));
		allowed.put(ReportTargetType.COMMUNITY_POST, EnumSet.of(HIDE_COMMUNITY_POST, SUSPEND_USER, NONE));
		allowed.put(ReportTargetType.COMMUNITY_COMMENT, EnumSet.of(HIDE_COMMUNITY_COMMENT, SUSPEND_USER, NONE));
		return Collections.unmodifiableMap(allowed);
	}

}
