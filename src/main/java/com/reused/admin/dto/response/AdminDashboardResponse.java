package com.reused.admin.dto.response;

/**
 * 운영 현황 집계(관리자 대시보드 조회 명세). 모든 값은 0 이상이며 null이 아니다.
 *
 * <p>회원 수는 DB 상태값 기준이다. 기간이 끝난 정지는 만료 해제 작업이 주기적으로 ACTIVE로 되돌린다.
 *
 * @param totalUsers 탈퇴 회원 포함 전체 회원(명세 예시 1250 = 1198 + 12 + 탈퇴 40)
 * @param pendingReports 미처리 신고. DDL의 "미처리 상태" 정의대로 RECEIVED와 IN_REVIEW를 센다
 */
public record AdminDashboardResponse(
		long totalUsers,
		long activeUsers,
		long suspendedUsers,
		long totalListings,
		long onSaleListings,
		long completedTrades,
		long pendingReports) {
}
