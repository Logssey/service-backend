package com.reused.listing.query;

/** Query parameters for the public listing feed. All fields are optional. */
public record ListingSearchRequest(
		String keyword,
		Long categoryId,
		String status,
		String itemCondition,
		Integer minPrice,
		Integer maxPrice,
		String sort,
		String cursor,
		Integer size) {
}
