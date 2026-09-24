package com.reused.image.storage;

import java.time.Duration;
import java.util.Optional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

final class UnavailableImageStorage implements ImageStorage {

	private BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이미지 저장소가 설정되지 않았습니다.");
	}

	@Override
	public String presignUpload(String objectKey, String contentType, Duration ttl) {
		throw unavailable();
	}

	@Override
	public Optional<StoredImage> inspect(String objectKey) {
		throw unavailable();
	}

	@Override
	public boolean promote(String sourceKey, String verifiedKey, String expectedEtag) {
		throw unavailable();
	}

	@Override
	public String presignRead(String objectKey, Duration ttl) {
		throw unavailable();
	}

	@Override
	public void delete(String objectKey) {
		throw unavailable();
	}
}
