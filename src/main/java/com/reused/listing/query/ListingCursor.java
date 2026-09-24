package com.reused.listing.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/** Internal position of the last item returned by a listing search. */
record ListingCursor(String sort, int price, Instant createdAt, long listingId) {

	private static final String VERSION = "1";

	static ListingCursor decode(String encoded, String requestedSort) {
		if (encoded.length() > 512) {
			throw invalidCursor();
		}
		try {
			String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			String[] parts = value.split("\\|", -1);
			if (parts.length != 5 || !VERSION.equals(parts[0]) || !requestedSort.equals(parts[1])) {
				throw new IllegalArgumentException("Cursor version or sort does not match");
			}
			int price = Integer.parseInt(parts[2]);
			Instant createdAt = Instant.parse(parts[3]);
			long listingId = Long.parseLong(parts[4]);
			if (price < 0 || price > 100_000_000 || listingId <= 0) {
				throw new IllegalArgumentException("Cursor values are out of range");
			}
			return new ListingCursor(parts[1], price, createdAt, listingId);
		} catch (RuntimeException e) {
			throw invalidCursor();
		}
	}

	static String encode(String sort, ListingSummaryResponse lastItem) {
		String value = String.join("|", VERSION, sort, Integer.toString(lastItem.price()),
				lastItem.createdAt().toString(), Long.toString(lastItem.listingId()));
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static BusinessException invalidCursor() {
		return new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다.");
	}
}
