package com.reused.auth.dto.response;

import com.reused.user.dto.response.UserSummaryResponse;
import com.reused.user.entity.User;

public record AuthTokenResponse(String accessToken, UserSummaryResponse user) {

	public static AuthTokenResponse of(String accessToken, User user) {
		return new AuthTokenResponse(accessToken, UserSummaryResponse.from(user));
	}

}
