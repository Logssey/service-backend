package com.reused.trade.query;

public record TradeSearchRequest(String role, String status, String cursor, Integer size) {
}
