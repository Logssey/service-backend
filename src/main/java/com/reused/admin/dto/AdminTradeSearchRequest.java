package com.reused.admin.dto;

public record AdminTradeSearchRequest(
		String status,
		Long userId,
		String cursor,
		Integer size) {
}
