package com.reused.image.repository;

public record ImageRecord(Long imageId, Long listingId, Long uploaderId, String objectKey,
		String thumbnailKey, String contentType, long fileSize, String status, int displayOrder) {
}
