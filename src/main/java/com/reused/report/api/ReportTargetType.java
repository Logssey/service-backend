package com.reused.report.api;

/**
 * 신고 대상 종류. DB CHECK 제약(ck_reports_target_type)과 값이 같아야 한다.
 */
public enum ReportTargetType {
	LISTING,
	USER,
	MESSAGE,
	COMMUNITY_POST,
	COMMUNITY_COMMENT
}
