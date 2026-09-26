package com.reused.audit.api;

/**
 * 감사 로그 action 값(audit_logs.action, VARCHAR(50)). 문서가 정한 값은 {@code USER_SUSPEND}뿐이고
 * 나머지는 0단계 계약이 정했다. A 도메인 값까지 미리 넣어 두어 공통 파일을 따로 고칠 일이 없게 한다.
 */
public enum AuditAction {

	// 인증 (FR-LOG-001)
	AUTH_SIGNUP,
	AUTH_LOGIN,
	AUTH_LOGOUT,
	AUTH_PASSWORD_RESET,
	AUTH_PASSWORD_CHANGE,
	AUTH_TOKEN_REUSE_DETECTED,

	// 회원 상태·역할 (FR-LOG-004, FR-LOG-005)
	USER_WITHDRAW,
	USER_SUSPEND,
	USER_ACTIVATE,
	USER_ROLE_GRANT,
	USER_ROLE_REVOKE,

	// 신고 처리
	REPORT_HANDLE,

	// 공지
	NOTICE_CREATE,
	NOTICE_UPDATE,
	NOTICE_DELETE,

	// 게시글 작성자 행위 — A (FR-LOG-002). LISTING_DELETE는 upstream 관리자 삭제(AdminService)도 쓴다
	LISTING_CREATE,
	LISTING_UPDATE,
	LISTING_DELETE,
	// A의 관리자 게시글 API(AdminService)가 쓰는 값. 감사 로그 조회 필터로 찾을 수 있게 둔다. LISTING_DELETE는 관리자 삭제에도 같은 문자열이 쓰인다.
	LISTING_HIDE,
	LISTING_RESTORE,

	// 관리자 게시글 조치 — A의 관리자 API, 그리고 B의 신고 처리 조치
	ADMIN_LISTING_HIDE,
	ADMIN_LISTING_RESTORE,
	ADMIN_LISTING_DELETE,

	// 신고 처리 조치
	COMMUNITY_POST_HIDE,
	COMMUNITY_COMMENT_HIDE,

	// 외부 연동 결과 (FR-LOG-006)
	EXTERNAL_KAKAO,
	EXTERNAL_MAIL,
	EXTERNAL_LLM,
	EXTERNAL_S3

}
