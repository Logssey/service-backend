package com.reused.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

record ChatRoomCursor(Instant activityAt, long roomId) {
	static ChatRoomCursor decode(String value, long userId) {
		try {
			if (value.length() > 512) throw new IllegalArgumentException();
			String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
			if (parts.length != 4 || !"chat-rooms-v1".equals(parts[0]) || Long.parseLong(parts[1]) != userId)
				throw new IllegalArgumentException();
			Instant time = Instant.parse(parts[2]);
			long id = Long.parseLong(parts[3]);
			if (id <= 0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
					|| time.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw new IllegalArgumentException();
			return new ChatRoomCursor(time, id);
		}
		catch (RuntimeException ex) { throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다."); }
	}
	String encode(long userId) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				("chat-rooms-v1|" + userId + "|" + activityAt + "|" + roomId).getBytes(StandardCharsets.UTF_8));
	}
}
