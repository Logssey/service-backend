package com.reused.image.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.image.dto.ImageUploadResultResponse;
import com.reused.image.dto.ImageUploadUrlRequest;
import com.reused.image.dto.ImageUploadUrlResponse;
import com.reused.image.repository.ImageRecord;
import com.reused.image.repository.ImageRepository;
import com.reused.image.storage.ImageStorage;
import com.reused.image.storage.ImageStorageException;
import com.reused.image.storage.StoredImage;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.repository.UserRepository;

@Service
public class ImageService {

	private static final Logger log = LoggerFactory.getLogger(ImageService.class);

	private static final long MAX_SIZE = 10L * 1024 * 1024;
	private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
	private static final Duration READ_URL_TTL = Duration.ofMinutes(15);
	private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

	private final ImageRepository repository;
	private final UserRepository users;
	private final ImageStorage storage;

	public ImageService(ImageRepository repository, UserRepository users, ImageStorage storage) {
		this.repository = repository;
		this.users = users;
		this.storage = storage;
	}

	@Transactional
	public ImageUploadUrlResponse issueUploadUrl(AuthPrincipal principal, ImageUploadUrlRequest request) {
		Long uploaderId = requireActiveUser(principal);
		if (!"LISTING".equals(request.purpose())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "상품 이미지 업로드만 지원합니다.");
		}
		if (!CONTENT_TYPES.contains(request.contentType()) || request.fileSize() == null
				|| request.fileSize() < 1 || request.fileSize() > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "이미지 형식 또는 크기가 올바르지 않습니다.");
		}
		if (!validExtension(request.fileName(), request.contentType())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "파일 확장자와 이미지 형식이 일치하지 않습니다.");
		}
		String extension = switch (request.contentType()) {
			case "image/jpeg" -> ".jpg";
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			default -> throw new IllegalStateException("이미지 형식 검사 누락");
		};
		String key = "pending/listing-images/" + uploaderId + "/" + UUID.randomUUID() + extension;
		Long imageId = repository.insertPending(uploaderId, key, request.contentType(), request.fileSize());
		Instant expiresAt = Instant.now().plus(UPLOAD_URL_TTL);
		try {
			String url = storage.presignUpload(key, request.contentType(), UPLOAD_URL_TTL);
			return new ImageUploadUrlResponse(imageId, url, expiresAt);
		}
		catch (ImageStorageException ex) {
			throw storageFailure("이미지 업로드 URL 발급에 실패했습니다.", ex);
		}
	}

	/** Validation failures persist REJECTED so a failed object can never be attached. */
	@Transactional(noRollbackFor = RejectedImageException.class)
	public ImageUploadResultResponse complete(AuthPrincipal principal, Long imageId) {
		Long uploaderId = requireActiveUser(principal);
		ImageRecord image = ownedImageForUpdate(imageId, uploaderId);
		if ("REJECTED".equals(image.status())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "이미지 검증에 실패했습니다.");
		}
		String verifiedKey = image.objectKey();
		if (!"VERIFIED".equals(image.status())) {
			Optional<StoredImage> stored;
			try {
				stored = storage.inspect(image.objectKey());
			}
			catch (ImageStorageException ex) {
				throw storageFailure("이미지 객체를 확인할 수 없습니다.", ex);
			}
			if (stored.isEmpty() || !validObject(image, stored.get())) {
				repository.updateStatus(imageId, "REJECTED");
				throw new RejectedImageException("업로드된 이미지의 형식 또는 크기가 올바르지 않습니다.");
			}
			verifiedKey = image.objectKey().replaceFirst("^pending/", "verified/");
			boolean copied;
			try {
				copied = storage.promote(image.objectKey(), verifiedKey, stored.get().etag());
			}
			catch (ImageStorageException ex) {
				throw storageFailure("검증된 이미지 객체를 저장할 수 없습니다.", ex);
			}
			if (!copied) {
				repository.updateStatus(imageId, "REJECTED");
				throw new RejectedImageException("업로드 중 이미지 객체가 변경되었습니다.");
			}
			repository.markVerified(imageId, verifiedKey);
			cleanupPendingAfterCommit(imageId, image.objectKey());
		}
		try {
			String url = storage.presignRead(verifiedKey, READ_URL_TTL);
			return new ImageUploadResultResponse(imageId, "VERIFIED", url, url);
		}
		catch (ImageStorageException ex) {
			throw storageFailure("이미지 조회 URL 발급에 실패했습니다.", ex);
		}
	}

	@Transactional
	public void delete(AuthPrincipal principal, Long imageId) {
		Long uploaderId = requireActiveUser(principal);
		ImageRecord image = ownedImageForUpdate(imageId, uploaderId);
		if (image.listingId() != null) {
			throw new BusinessException(ErrorCode.CONFLICT, "게시글에 연결된 이미지는 게시글 수정에서 해제해야 합니다.");
		}
		repository.delete(imageId);
		cleanupObjectAfterCommit(imageId, image.objectKey());
	}

	private Long requireActiveUser(AuthPrincipal principal) {
		if (principal.role() != UserRole.USER) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		User user = users.findById(principal.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (user.getRole() != UserRole.USER || user.isWithdrawn()) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (user.isSuspended()) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}
		return user.getId();
	}

	private ImageRecord ownedImageForUpdate(Long imageId, Long uploaderId) {
		if (imageId == null || imageId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		ImageRecord image = repository.findForUpdate(imageId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!image.uploaderId().equals(uploaderId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return image;
	}

	private static boolean validObject(ImageRecord image, StoredImage actual) {
		if (actual.fileSize() < 1 || actual.fileSize() > MAX_SIZE
				|| actual.fileSize() != image.fileSize()
				|| !image.contentType().equals(actual.contentType())
				|| actual.etag() == null || actual.etag().isBlank()) {
			return false;
		}
		byte[] bytes = actual.bytes();
		if (bytes == null || bytes.length != actual.fileSize()) {
			return false;
		}
		boolean hasExpectedSignature = switch (image.contentType()) {
			case "image/jpeg" -> bytes.length >= 3 && u(bytes[0]) == 0xff
					&& u(bytes[1]) == 0xd8 && u(bytes[2]) == 0xff
					&& bytes.length >= 4 && u(bytes[bytes.length - 2]) == 0xff
					&& u(bytes[bytes.length - 1]) == 0xd9;
			case "image/png" -> bytes.length >= 8 && u(bytes[0]) == 0x89
					&& bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
					&& u(bytes[4]) == 0x0d && u(bytes[5]) == 0x0a
					&& u(bytes[6]) == 0x1a && u(bytes[7]) == 0x0a
					&& endsWithPngIend(bytes);
			case "image/webp" -> bytes.length >= 12 && bytes[0] == 'R'
					&& bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
					&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P'
					&& riffLength(bytes) == bytes.length - 8;
			default -> false;
		};
		return hasExpectedSignature && decodesAsImage(bytes, image.contentType());
	}

	private static boolean endsWithPngIend(byte[] bytes) {
		byte[] end = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xae, 'B', '`', (byte) 0x82};
		if (bytes.length < end.length) {
			return false;
		}
		for (int index = 0; index < end.length; index++) {
			if (bytes[bytes.length - end.length + index] != end[index]) {
				return false;
			}
		}
		return true;
	}

	private static long riffLength(byte[] bytes) {
		return (long) u(bytes[4]) | ((long) u(bytes[5]) << 8)
				| ((long) u(bytes[6]) << 16) | ((long) u(bytes[7]) << 24);
	}

	private static boolean decodesAsImage(byte[] bytes, String contentType) {
		String expected = switch (contentType) {
			case "image/jpeg" -> "JPEG";
			case "image/png" -> "PNG";
			case "image/webp" -> "WebP";
			default -> throw new IllegalArgumentException("지원하지 않는 이미지 형식");
		};
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return false;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				if (!expected.equalsIgnoreCase(reader.getFormatName())) {
					return false;
				}
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || (long) width * height > 100_000_000L) {
					return false;
				}
				ImageReadParam params = reader.getDefaultReadParam();
				int sample = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / 4_000_000)));
				params.setSourceSubsampling(sample, sample, 0, 0);
				return reader.read(0, params) != null;
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			return false;
		}
	}

	private static boolean validExtension(String fileName, String contentType) {
		if (fileName == null) {
			return false;
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		return switch (contentType) {
			case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
			case "image/png" -> lower.endsWith(".png");
			case "image/webp" -> lower.endsWith(".webp");
			default -> false;
		};
	}

	private void cleanupPendingAfterCommit(Long imageId, String pendingKey) {
		cleanupObjectAfterCommit(imageId, pendingKey);
	}

	private void cleanupObjectAfterCommit(Long imageId, String objectKey) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					storage.delete(objectKey);
				}
				catch (RuntimeException ex) {
					log.warn("이미지 객체 정리 실패: imageId={}", imageId, ex);
				}
			}
		});
	}

	private static int u(byte value) {
		return Byte.toUnsignedInt(value);
	}

	private static BusinessException storageFailure(String message, ImageStorageException cause) {
		return new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
	}

	private static final class RejectedImageException extends BusinessException {
		private RejectedImageException(String message) {
			super(ErrorCode.INVALID_INPUT, message);
		}
	}
}
