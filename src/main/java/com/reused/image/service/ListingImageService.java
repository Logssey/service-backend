package com.reused.image.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.image.repository.ImageRecord;
import com.reused.image.repository.ImageRepository;
import com.reused.image.storage.ImageStorage;
import com.reused.image.storage.ImageStorageException;
import com.reused.listing.query.ListingImageResponse;

@Service
public class ListingImageService {

	private static final Duration READ_URL_TTL = Duration.ofMinutes(15);

	private final ImageRepository repository;
	private final ImageStorage storage;

	public ListingImageService(ImageRepository repository, ImageStorage storage) {
		this.repository = repository;
		this.storage = storage;
	}

	@Transactional
	public void attachToNewListing(Long listingId, Long uploaderId, List<Long> imageIds) {
		if (imageIds == null || imageIds.isEmpty()) {
			return;
		}
		validateIds(imageIds);
		List<ImageRecord> selected = repository.findAllForUpdate(imageIds);
		validateSelected(listingId, uploaderId, imageIds, selected);
		for (int index = 0; index < imageIds.size(); index++) {
			repository.attach(imageIds.get(index), listingId, index);
		}
	}

	@Transactional
	public void replaceForListing(Long listingId, Long uploaderId, List<Long> imageIds) {
		if (imageIds == null) {
			return;
		}
		validateIds(imageIds);
		List<ImageRecord> selected = repository.findAllForUpdate(imageIds);
		validateSelected(listingId, uploaderId, imageIds, selected);
		repository.detachAllFromListing(listingId);
		for (int index = 0; index < imageIds.size(); index++) {
			repository.attach(imageIds.get(index), listingId, index);
		}
	}

	@Transactional(readOnly = true)
	public List<ListingImageResponse> imagesForListing(Long listingId) {
		return repository.findByListing(listingId).stream()
				.map(image -> new ListingImageResponse(image.imageId(),
						readUrl(image.objectKey()), image.displayOrder()))
				.toList();
	}

	@Transactional(readOnly = true)
	public Map<Long, String> thumbnailsForListings(List<Long> listingIds) {
		if (listingIds == null || listingIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> urls = new HashMap<>();
		repository.thumbnailKeys(listingIds).forEach((id, key) -> urls.put(id, readUrl(key)));
		return urls;
	}

	private static void validateIds(List<Long> ids) {
		if (ids.size() > 5 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "이미지는 최대 5장까지 연결할 수 있습니다.");
		}
		Set<Long> unique = new HashSet<>(ids);
		if (unique.size() != ids.size()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "중복된 이미지가 있습니다.");
		}
	}

	private static void validateSelected(Long listingId, Long uploaderId, List<Long> ids,
			List<ImageRecord> selected) {
		if (selected.size() != ids.size()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 이미지가 있습니다.");
		}
		for (ImageRecord image : selected) {
			if (!image.uploaderId().equals(uploaderId)) {
				throw new BusinessException(ErrorCode.FORBIDDEN);
			}
			if (!"VERIFIED".equals(image.status())) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "검증되지 않은 이미지가 있습니다.");
			}
			if (image.listingId() != null && !image.listingId().equals(listingId)) {
				throw new BusinessException(ErrorCode.CONFLICT, "이미 다른 게시글에 연결된 이미지가 있습니다.");
			}
		}
	}

	private String readUrl(String key) {
		try {
			return storage.presignRead(key, READ_URL_TTL);
		}
		catch (ImageStorageException ex) {
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "이미지 조회 URL 발급에 실패했습니다.", ex);
		}
	}
}
