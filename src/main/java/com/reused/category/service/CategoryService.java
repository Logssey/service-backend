package com.reused.category.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.category.dto.response.CategoryResponse;
import com.reused.category.repository.CategoryRepository;

@Service
public class CategoryService {

	private final CategoryRepository categoryRepository;

	public CategoryService(CategoryRepository categoryRepository) {
		this.categoryRepository = categoryRepository;
	}

	@Transactional(readOnly = true)
	public List<CategoryResponse> getActiveCategories() {
		return categoryRepository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc()
				.stream()
				.map(CategoryResponse::from)
				.toList();
	}

}
