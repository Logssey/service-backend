package com.reused.user.dto.response;

/**
 * 확인 시점의 결과이며 예약이 아니다. 가입 확정은 서버의 UNIQUE 제약이 판단한다(닉네임 중복 확인 명세).
 */
public record NicknameAvailabilityResponse(boolean available) {
}
