package com.reused.admin.dto;

import java.time.Instant;

import com.reused.user.dto.response.UserSummaryResponse;

public record AdminListingResponse(
		Long listingId,
		String title,
		int price,
		String status,
		UserSummaryResponse seller,
		long reportCount,
		boolean isDeleted,
		Instant createdAt) {
}
