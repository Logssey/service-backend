package com.reused.category.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.category.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	List<Category> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();

}
