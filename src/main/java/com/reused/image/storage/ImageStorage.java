package com.reused.image.storage;

import java.time.Duration;
import java.util.Optional;

/** Operations against a private object store. URLs are short lived and object keys stay server-owned. */
public interface ImageStorage {

	String presignUpload(String objectKey, String contentType, long contentLength, Duration ttl);

	Optional<StoredImage> inspect(String objectKey);

	/** Atomically copies the exact inspected source version to a key with no upload URL. */
	boolean promote(String sourceKey, String verifiedKey, String expectedEtag);

	String presignRead(String objectKey, Duration ttl);

	void delete(String objectKey);
}
