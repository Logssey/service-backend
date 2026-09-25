package com.reused.trade.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

record TradeCursor(Instant requestedAt, long tradeId) {

	private static final String VERSION = "1";

	static TradeCursor decode(String encoded) {
		if (encoded.length() > 512) {
			throw invalidCursor();
		}
		try {
			String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			String[] parts = value.split("\\|", -1);
			if (parts.length != 3 || !VERSION.equals(parts[0])) {
				throw new IllegalArgumentException("Unsupported cursor");
			}
			Instant requestedAt = Instant.parse(parts[1]);
			long tradeId = Long.parseLong(parts[2]);
			if (tradeId <= 0) {
				throw new IllegalArgumentException("Invalid trade id");
			}
			return new TradeCursor(requestedAt, tradeId);
		}
		catch (RuntimeException e) {
			throw invalidCursor();
		}
	}

	static String encode(TradeSummaryResponse lastItem) {
		String value = String.join("|", VERSION, lastItem.requestedAt().toString(),
				Long.toString(lastItem.tradeId()));
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static BusinessException invalidCursor() {
		return new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다.");
	}
}
