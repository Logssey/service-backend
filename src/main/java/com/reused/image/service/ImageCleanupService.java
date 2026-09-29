package com.reused.image.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.reused.image.repository.ImageRecord;
import com.reused.image.repository.ImageRepository;
import com.reused.image.storage.ImageStorage;

/**
 * Removes upload metadata and objects that were never attached or were detached by a listing update.
 * Claimed rows remain as REJECTED tombstones when object storage is unavailable, so the next run can retry.
 */
@Service
public class ImageCleanupService {

	private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);
	private static final int BATCH_SIZE = 100;

	private final ImageRepository repository;
	private final ImageStorage storage;
	private final Duration retention;

	public ImageCleanupService(ImageRepository repository, ImageStorage storage,
			@Value("${app.image.orphan-retention:24h}") Duration retention) {
		this.repository = repository;
		this.storage = storage;
		this.retention = retention;
	}

	@Scheduled(initialDelayString = "${app.image.cleanup-interval:1h}",
			fixedDelayString = "${app.image.cleanup-interval:1h}")
	public void cleanupExpiredOrphans() {
		for (ImageRecord image : repository.claimExpiredOrphans(Instant.now().minus(retention), BATCH_SIZE)) {
			try {
				deleteStoredObjects(image.objectKey());
				repository.deleteClaimedOrphan(image.imageId());
			}
			catch (RuntimeException ex) {
				log.warn("고아 이미지 정리 실패, 다음 주기에 재시도: imageId={}", image.imageId(), ex);
			}
		}
	}

	private void deleteStoredObjects(String objectKey) {
		storage.delete(objectKey);
		// A promotion can succeed immediately before its DB transaction rolls back.
		if (objectKey.startsWith("pending/")) {
			storage.delete(objectKey.replaceFirst("^pending/", "verified/"));
		}
	}
}
