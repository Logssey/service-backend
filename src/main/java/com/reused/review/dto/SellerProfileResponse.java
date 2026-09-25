package com.reused.review.dto;

import java.time.Instant;

public record SellerProfileResponse(Long userId, String nickname, String profileImageUrl, String bio,
        long completedTradeCount, Double averageRating, long reviewCount, Instant joinedAt,
        boolean reportFlag) {
}
