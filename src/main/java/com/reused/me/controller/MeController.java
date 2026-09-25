package com.reused.me.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.listing.query.CursorPageResponse;
import com.reused.me.service.MyListingService;
import com.reused.me.service.MyListingService.MyListingResponse;
import com.reused.review.dto.ReviewResponse;
import com.reused.review.service.ReviewService;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final ReviewService reviews;
    private final MyListingService listings;
    public MeController(ReviewService reviews, MyListingService listings) {
        this.reviews = reviews;
        this.listings = listings;
    }

    @GetMapping("/reviews")
    public CursorPageResponse<ReviewResponse> received(@AuthUser AuthPrincipal principal,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return reviews.mine(principal, cursor, size);
    }

    @GetMapping("/selling/listings")
    public CursorPageResponse<MyListingResponse> selling(@AuthUser AuthPrincipal principal,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return listings.mine(principal, status, cursor, size);
    }
}
