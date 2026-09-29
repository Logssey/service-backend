package com.reused.review.dto;

import java.time.Instant;
import com.reused.image.service.ProfileImageUrlSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

public record SellerProfileResponse(Long userId, String nickname,
        @JsonSerialize(using = ProfileImageUrlSerializer.class) String profileImageUrl, String bio,
        long completedTradeCount, Double averageRating, long reviewCount, Instant joinedAt,
        boolean reportFlag) {
}
