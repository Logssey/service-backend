package com.reused.admin.dto;

public record AdminListingSearchRequest(
		String status,
		String keyword,
		Long sellerId,
		String cursor,
		Integer size) {
}
