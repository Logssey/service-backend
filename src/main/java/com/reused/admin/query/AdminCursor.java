package com.reused.admin.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

public record AdminCursor(Instant createdAt, long id) {

	private static final String VERSION = "1";

	public static AdminCursor decode(String encoded, String scope) {
		if (encoded == null || encoded.length() > 512) {
			throw invalid();
		}
		try {
			String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			String[] parts = value.split("\\|", -1);
			if (parts.length != 4 || !VERSION.equals(parts[0]) || !scope.equals(parts[1])) {
				throw new IllegalArgumentException("Unsupported cursor");
			}
			Instant createdAt = Instant.parse(parts[2]);
			long id = Long.parseLong(parts[3]);
			if (id <= 0) {
				throw new IllegalArgumentException("Invalid id");
			}
			return new AdminCursor(createdAt, id);
		}
		catch (RuntimeException ex) {
			throw invalid();
		}
	}

	public static String encode(String scope, Instant createdAt, Long id) {
		String value = String.join("|", VERSION, scope, createdAt.toString(), id.toString());
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다.");
	}
}
