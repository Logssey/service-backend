package com.reused.review.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.CurrentUserProvider;
import com.reused.listing.query.CursorPageResponse;
import com.reused.listing.query.ListingSummaryResponse;
import com.reused.me.service.MyListingService;
import com.reused.review.dto.ReviewResponse;
import com.reused.review.dto.SellerProfileResponse;
import com.reused.review.service.ReviewService;

@RestController
@RequestMapping("/api/v1/users/{userId}")
public class SellerProfileController {
    private final ReviewService reviews;
    private final MyListingService listings;
    private final CurrentUserProvider currentUser;
    public SellerProfileController(ReviewService reviews, MyListingService listings, CurrentUserProvider currentUser) {
        this.reviews = reviews;
        this.listings = listings;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    public SellerProfileResponse profile(@PathVariable Long userId) { return reviews.profile(userId); }

    @GetMapping("/reviews")
    public CursorPageResponse<ReviewResponse> received(@PathVariable Long userId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return reviews.received(userId, cursor, size);
    }

    @GetMapping("/listings")
    public CursorPageResponse<ListingSummaryResponse> listings(@PathVariable Long userId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        Long viewerId = currentUser.current().map(AuthPrincipal::userId).orElse(null);
        return listings.publicListings(userId, viewerId, cursor, size);
    }
}
