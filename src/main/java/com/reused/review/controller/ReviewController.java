package com.reused.review.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.review.dto.ReviewCreateRequest;
import com.reused.review.service.ReviewService;
import com.reused.review.service.ReviewService.ReviewCreateResponse;

import jakarta.validation.Valid;

@RestController
public class ReviewController {
    private final ReviewService reviews;
    public ReviewController(ReviewService reviews) { this.reviews = reviews; }

    @PostMapping("/api/v1/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewCreateResponse create(@AuthUser AuthPrincipal principal,
            @Valid @RequestBody ReviewCreateRequest request) {
        return reviews.create(principal, request);
    }
}
