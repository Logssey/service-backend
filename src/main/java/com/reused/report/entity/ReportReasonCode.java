package com.reused.report.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.reused.report.api.ReportTargetType;

/**
 * 신고 사유(신고 접수 명세). reports.reason_code VARCHAR(30)에 저장한다. DB CHECK가 없어 이 enum이 허용값을 정한다.
 *
 * <p>대상 유형마다 고를 수 있는 사유가 다르며 서버가 검증한다. 프론트가 같은 표를 쓰므로 표를 바꾸면 양쪽을 함께 고친다.
 */
public enum ReportReasonCode {
	/** 금지 품목 */
	PROHIBITED_ITEM,
	/** 허위 정보 */
	FALSE_INFO,
	/** 약속 불이행 */
	NO_SHOW,
	/** 음란성 내용 */
	SEXUAL_CONTENT,
	/** 사기 의심 */
	FRAUD_SUSPICION,
	/** 욕설·비방 */
	ABUSIVE_BEHAVIOR,
	/** 광고·도배 */
	SPAM,
	/** 기타 */
	OTHER;

	private static final Map<ReportTargetType, Set<ReportReasonCode>> ALLOWED = allowedByTargetType();

	public boolean isAllowedFor(ReportTargetType targetType) {
		return ALLOWED.get(targetType).contains(this);
	}

	/**
	 * 신고 접수 명세의 "대상 유형별 허용 코드" 표.
	 */
	private static Map<ReportTargetType, Set<ReportReasonCode>> allowedByTargetType() {
		Map<ReportTargetType, Set<ReportReasonCode>> allowed = new EnumMap<>(ReportTargetType.class);
		allowed.put(ReportTargetType.LISTING, EnumSet.of(PROHIBITED_ITEM, FALSE_INFO, FRAUD_SUSPICION, SPAM, OTHER));
		allowed.put(ReportTargetType.USER, EnumSet.of(FRAUD_SUSPICION, ABUSIVE_BEHAVIOR, NO_SHOW, OTHER));
		allowed.put(ReportTargetType.MESSAGE, EnumSet.of(ABUSIVE_BEHAVIOR, SEXUAL_CONTENT, SPAM, OTHER));
		allowed.put(ReportTargetType.COMMUNITY_POST,
				EnumSet.of(FALSE_INFO, ABUSIVE_BEHAVIOR, SEXUAL_CONTENT, SPAM, OTHER));
		allowed.put(ReportTargetType.COMMUNITY_COMMENT, EnumSet.of(ABUSIVE_BEHAVIOR, SEXUAL_CONTENT, SPAM, OTHER));
		return Collections.unmodifiableMap(allowed);
	}

}
