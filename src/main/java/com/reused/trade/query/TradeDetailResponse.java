package com.reused.trade.query;

import java.util.List;

import com.reused.user.dto.response.UserSummaryResponse;

public record TradeDetailResponse(
		Long tradeId,
		String status,
		ListingBriefResponse listing,
		UserSummaryResponse seller,
		UserSummaryResponse buyer,
		String myRole,
		Long chatRoomId,
		boolean reviewWritten,
		List<TradeHistoryResponse> histories) {
}
