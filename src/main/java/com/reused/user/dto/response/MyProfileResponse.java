package com.reused.user.dto.response;

import java.time.Instant;

import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;
import com.reused.image.service.ProfileImageUrlSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 본인 정보(내 정보 조회 명세).
 *
 * <p>{@code provider}·{@code email}·{@code emailVerified}는 이 본인 응답에만 둔다.
 * 공용 {@link UserSummaryResponse}에 넣으면 다른 사용자의 인증 수단·이메일·인증 상태까지 노출된다.
 *
 * <p>{@code email}은 인증 수단에 등록된 본인 주소다. LOCAL은 항상 있고, 소셜은 온보딩에서 입력했을 때만 있으며
 * 없으면 null이다(ADR-019). 본인이 오타를 확인할 수 있게 주소를 그대로 싣는다.
 * {@code emailVerified}는 등록된 이메일의 소유 확인 여부이며 이메일이 없으면 false다.
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
		String email,
		boolean emailVerified,
		Instant createdAt) {
}
