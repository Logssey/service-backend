package com.reused.audit.api;

/**
 * 감사 대상 종류(audit_logs.target_type, VARCHAR(20)). 외부 연동 기록은 {@code EXTERNAL}을 쓴다.
 */
public enum AuditTargetType {
	USER,
	LISTING,
	TRADE,
	REPORT,
	NOTICE,
	MESSAGE,
	COMMUNITY_POST,
	COMMUNITY_COMMENT,
	EXTERNAL
}
