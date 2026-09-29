package com.reused.review.dto;

import java.time.Instant;
import com.reused.user.dto.response.UserSummaryResponse;

public record ReviewResponse(Long reviewId, UserSummaryResponse reviewer, int rating,
        String content, Instant createdAt) {
}
