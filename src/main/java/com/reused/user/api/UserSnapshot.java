package com.reused.user.api;

import java.time.Instant;

import com.reused.user.dto.response.UserSummaryResponse;
import com.reused.user.entity.UserStatus;

/**
 * 다른 도메인에 건네는 회원 조회 결과. User 엔티티를 넘기지 않는다(분배 원칙).
 *
 * <p>탈퇴 회원의 nickname은 이미 {@code 탈퇴회원#{userId}}로 바뀌어 있다. 중고거래·거래·후기 응답은
 * 그 값을 그대로 쓰고, 커뮤니티만 {@link #withdrawn()}으로 익명화한다.
 *
 * @param status 탈퇴 시각만 기록된 비정상 행도 WITHDRAWN으로 담긴다
 */
public record UserSnapshot(Long userId, String nickname, String profileImageUrl, UserStatus status,
		Instant createdAt) {

	public boolean withdrawn() {
		return status == UserStatus.WITHDRAWN;
	}

	public UserSummaryResponse toSummary() {
		return new UserSummaryResponse(userId, nickname, profileImageUrl);
	}

}
