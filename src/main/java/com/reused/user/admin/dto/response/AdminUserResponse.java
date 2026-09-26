package com.reused.user.admin.dto.response;

import java.time.Instant;

import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;

/**
 * 관리자 회원 목록의 한 행(회원 목록 조회 명세). 이메일·인증 수단·소개·프로필 이미지는 싣지 않는다(NFR-DATA-011).
 *
 * @param nickname 탈퇴 회원은 {@code 탈퇴회원#{userId}} 그대로
 * @param status DB 상태값. 탈퇴 시각이 있으면 WITHDRAWN
 * @param suspendedUntil SUSPENDED가 아니면 null. SUSPENDED이면서 null이면 무기한
 * @param listingCount 삭제되지 않은 게시글 수(A 제공). A 구현이 없으면 0
 * @param reportedCount 이 회원을 대상(target_type = 'USER')으로 접수된 신고의 전체 건수. 모든 상태를 센다
 */
public record AdminUserResponse(
		Long userId,
		String nickname,
		UserRole role,
		UserStatus status,
		Instant suspendedUntil,
		long listingCount,
		long reportedCount,
		Instant createdAt) {
}
