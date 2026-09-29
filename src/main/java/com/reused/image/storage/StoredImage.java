package com.reused.image.storage;

/** Metadata and complete object bytes, read against the same ETag. */
public record StoredImage(long fileSize, String contentType, byte[] bytes, String etag) {
}
