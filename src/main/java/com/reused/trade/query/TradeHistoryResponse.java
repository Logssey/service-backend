package com.reused.trade.query;

import java.time.Instant;

public record TradeHistoryResponse(String status, Instant changedAt, String reason) {
}
