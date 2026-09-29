package com.reused.listing.query;

import java.time.Instant;

import com.reused.user.dto.response.UserSummaryResponse;

public record ListingSummaryResponse(
		Long listingId,
		String title,
		int price,
		String status,
		String itemCondition,
		String thumbnailUrl,
		int wishCount,
		UserSummaryResponse seller,
		Instant createdAt) {
}
