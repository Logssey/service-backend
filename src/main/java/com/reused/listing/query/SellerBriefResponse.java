package com.reused.listing.query;

import com.reused.image.service.ProfileImageUrlSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

public record SellerBriefResponse(
		Long userId,
		String nickname,
		@JsonSerialize(using = ProfileImageUrlSerializer.class) String profileImageUrl,
		long completedTradeCount,
		Double averageRating) {
}
