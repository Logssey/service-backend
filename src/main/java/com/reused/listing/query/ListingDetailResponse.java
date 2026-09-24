package com.reused.listing.query;

import java.time.Instant;
import java.util.List;

import com.reused.category.dto.response.CategoryResponse;

public record ListingDetailResponse(
		Long listingId,
		String title,
		String description,
		int price,
		String itemCondition,
		String tradeMethod,
		String status,
		CategoryResponse category,
		List<ListingImageResponse> images,
		int wishCount,
		int viewCount,
		boolean isWished,
		boolean isMine,
		SellerBriefResponse seller,
		Instant createdAt) {
}
