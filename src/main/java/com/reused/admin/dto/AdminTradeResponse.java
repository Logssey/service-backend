package com.reused.admin.dto;

import java.time.Instant;

import com.reused.trade.query.ListingBriefResponse;
import com.reused.user.dto.response.UserSummaryResponse;

public record AdminTradeResponse(
		Long tradeId,
		String status,
		ListingBriefResponse listing,
		UserSummaryResponse seller,
		UserSummaryResponse buyer,
		Instant requestedAt,
		Instant completedAt) {
}
