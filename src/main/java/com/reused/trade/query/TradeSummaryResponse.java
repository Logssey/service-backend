package com.reused.trade.query;

import java.time.Instant;

import com.reused.user.dto.response.UserSummaryResponse;

public record TradeSummaryResponse(
		Long tradeId,
		String status,
		ListingBriefResponse listing,
		UserSummaryResponse counterparty,
		String myRole,
		Instant requestedAt,
		Instant completedAt,
		boolean reviewWritten) {
}
