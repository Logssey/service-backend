package com.reused.image.dto;

public record ImageUploadResultResponse(Long imageId, String status, String url, String thumbnailUrl) {
}
