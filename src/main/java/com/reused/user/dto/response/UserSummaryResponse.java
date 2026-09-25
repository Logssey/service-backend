package com.reused.user.dto.response;

import com.reused.user.entity.User;
import com.reused.image.service.ProfileImageUrlSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 여러 응답에 중첩되는 공용 사용자 요약(API 명세 §0.2).
 */
public record UserSummaryResponse(Long userId, String nickname,
		@JsonSerialize(using = ProfileImageUrlSerializer.class) String profileImageUrl) {

	public static UserSummaryResponse from(User user) {
		return new UserSummaryResponse(user.getId(), user.getNickname(), user.getProfileImageUrl());
	}

}
