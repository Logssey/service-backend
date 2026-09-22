package com.reused.category.dto.response;

import com.reused.category.entity.Category;

public record CategoryResponse(Long categoryId, String name) {

	public static CategoryResponse from(Category category) {
		return new CategoryResponse(category.getId(), category.getName());
	}

}
