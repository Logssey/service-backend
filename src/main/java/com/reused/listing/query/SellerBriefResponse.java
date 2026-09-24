package com.reused.listing.query;

public record SellerBriefResponse(
		Long userId,
		String nickname,
		String profileImageUrl,
		long completedTradeCount,
		Double averageRating) {
}
