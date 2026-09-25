package com.reused.wish;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/** The cursor follows the wish creation order, independently of the listing creation date. */
record WishCursor(Instant createdAt, long wishId) {

	static WishCursor decode(String encoded, long userId) {
		try {
			if (encoded.length() > 512) {
				throw new IllegalArgumentException();
			}
			String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
					.split("\\|", -1);
			if (parts.length != 4 || !"wishes-v1".equals(parts[0]) || Long.parseLong(parts[1]) != userId) {
				throw new IllegalArgumentException();
			}
			Instant createdAt = Instant.parse(parts[2]);
			long wishId = Long.parseLong(parts[3]);
			if (wishId <= 0 || createdAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
					|| createdAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
				throw new IllegalArgumentException();
			}
			return new WishCursor(createdAt, wishId);
		}
		catch (RuntimeException ex) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다.");
		}
	}

	String encode(long userId) {
		String value = "wishes-v1|" + userId + "|" + createdAt + "|" + wishId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
