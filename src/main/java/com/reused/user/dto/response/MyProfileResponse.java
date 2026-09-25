package com.reused.user.dto.response;

import java.time.Instant;

import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.User;
import com.reused.user.entity.UserIdentity;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;
import com.reused.image.service.ProfileImageUrlSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 본인 정보(내 정보 조회 명세).
 *
 * <p>{@code provider}와 {@code emailVerified}는 이 본인 응답에만 둔다.
 * 공용 {@link UserSummaryResponse}에 넣으면 다른 사용자의 인증 수단과 인증 상태까지 노출된다.
 */
public record MyProfileResponse(
		Long userId,
		String nickname,
		@JsonSerialize(using = ProfileImageUrlSerializer.class) String profileImageUrl,
		String bio,
		UserRole role,
		UserStatus status,
		Instant suspendedUntil,
		AuthProvider provider,
		boolean emailVerified,
		Instant createdAt) {

	/**
	 * {@code emailVerified}는 LOCAL 계정의 소유 확인 여부다. 소셜 계정은 항상 false다.
	 */
	public static MyProfileResponse of(User user, UserIdentity identity) {
		return new MyProfileResponse(
				user.getId(),
				user.getNickname(),
				user.getProfileImageUrl(),
				user.getBio(),
				user.getRole(),
				user.getStatus(),
				user.getSuspendedUntil(),
				identity.getProvider(),
				identity.isLocal() && identity.isEmailVerified(),
				user.getCreatedAt());
	}

}
