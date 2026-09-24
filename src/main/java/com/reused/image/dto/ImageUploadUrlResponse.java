package com.reused.image.dto;

import java.time.Instant;

public record ImageUploadUrlResponse(Long imageId, String uploadUrl, Instant expiresAt) {
}
