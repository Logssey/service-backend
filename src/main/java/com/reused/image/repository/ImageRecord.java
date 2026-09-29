package com.reused.image.repository;

import java.time.Instant;

public record ImageRecord(Long imageId, Long listingId, Long uploaderId, String objectKey,
		String thumbnailKey, String contentType, long fileSize, String status, int displayOrder,
		Instant createdAt, String purpose, Long profileUserId) {
}
