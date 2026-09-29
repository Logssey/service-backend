package com.reused.trade.dto.response;

import java.time.Instant;

public record TradeStatusResponse(Long tradeId, String status, Instant changedAt) {
}
