package com.reused.listing.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** All fields are optional. A null field keeps its current value. */
public record ListingUpdateRequest(
		@Size(min = 2, max = 100) @Pattern(regexp = "(?s).*\\S.*") String title,
		@Size(max = 2000) @Pattern(regexp = "(?s).*\\S.*") String description,
		@Min(0) @Max(100000000) Integer price,
		@Pattern(regexp = "NEW|LIKE_NEW|USED|DAMAGED") String itemCondition,
		@Pattern(regexp = "DIRECT|DELIVERY|BOTH") String tradeMethod,
		@Positive Long categoryId,
		@Size(max = 5) List<@NotNull @Positive Long> imageIds) {
}
