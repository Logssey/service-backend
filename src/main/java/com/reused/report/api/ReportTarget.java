package com.reused.report.api;

/**
 * 신고 대상 한 건의 요약.
 *
 * @param ownerUserId 대상의 작성자·소유자. 본인 대상 신고 거부(400)와 SUSPEND_USER의 정지 대상이다. USER 대상은 자기 자신
 * @param summary 관리자 화면 표시용 짧은 문구(게시글 제목, 닉네임 등). 신고 접수 응답에는 싣지 않는다
 */
public record ReportTarget(long targetId, long ownerUserId, String summary) {
}
