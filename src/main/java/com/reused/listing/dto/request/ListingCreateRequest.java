package com.reused.listing.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ListingCreateRequest(
		@NotBlank @Size(min = 2, max = 100) String title,
		@NotBlank @Size(max = 2000) String description,
		@NotNull @Min(0) @Max(100000000) Integer price,
		@NotNull @Pattern(regexp = "NEW|LIKE_NEW|USED|DAMAGED") String itemCondition,
		@NotNull @Pattern(regexp = "DIRECT|DELIVERY|BOTH") String tradeMethod,
		@NotNull @Positive Long categoryId,
		@Size(max = 5) List<@NotNull @Positive Long> imageIds) {
}
