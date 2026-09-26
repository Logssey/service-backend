package com.reused.notice.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.pagination.CursorCodec;
import com.reused.notice.entity.Notice;

/**
 * 공지 목록 커서. 정렬 키 {@code (isPinned, createdAt, noticeId)} 전체를 {@code {"p":true,"t":"...","id":8}}로 담는다.
 *
 * <p>t는 {@code Instant.toString()}이라 마이크로초까지 보존된다. 밀리초로 자르면 같은 밀리초 안의 공지가 누락되거나 중복된다.
 * 다른 목록의 커서({@code {"id":N}})나 키가 빠지거나 더 있는 커서는 해석하지 않고 400이다.
 */
record NoticeCursor(boolean pinned, Instant createdAt, long noticeId) {

	/** CursorCodec과 같은 문구. 무엇이 틀렸는지는 알려주지 않는다 */
	private static final String INVALID_MESSAGE = "커서가 올바르지 않습니다.";

	private static final String PINNED_KEY = "p";
	private static final String CREATED_AT_KEY = "t";
	private static final String ID_KEY = "id";
	private static final Set<String> KEYS = Set.of(PINNED_KEY, CREATED_AT_KEY, ID_KEY);
	private static final Instant MIN_CREATED_AT = Instant.parse("0001-01-01T00:00:00Z");
	private static final Instant MAX_CREATED_AT = Instant.parse("9999-12-31T23:59:59.999999Z");

	static String encode(Notice notice) {
		return CursorCodec.encode(Map.of(
				PINNED_KEY, notice.isPinned(),
				CREATED_AT_KEY, notice.getCreatedAt().toString(),
				ID_KEY, notice.getId()));
	}

	/**
	 * @return cursor가 null이거나 비어 있으면 null(첫 페이지)
	 * @throws BusinessException INVALID_INPUT 해석할 수 없는 커서
	 */
	static NoticeCursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		Map<String, Object> keys = CursorCodec.decode(cursor);
		if (!keys.keySet().equals(KEYS)
				|| !(keys.get(PINNED_KEY) instanceof Boolean pinned)
				|| !(keys.get(CREATED_AT_KEY) instanceof String createdAt)
				|| !(keys.get(ID_KEY) instanceof Integer || keys.get(ID_KEY) instanceof Long)) {
			throw invalid();
		}
		long noticeId = ((Number) keys.get(ID_KEY)).longValue();
		if (noticeId < 1) {
			throw invalid();
		}
		Instant parsed;
		try {
			parsed = Instant.parse(createdAt);
		}
		catch (DateTimeParseException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, INVALID_MESSAGE, e);
		}
		// 위조된 커서의 극단적인 연도가 TIMESTAMPTZ 범위를 넘어 쿼리가 500이 되지 않게 막는다.
		if (parsed.isBefore(MIN_CREATED_AT) || parsed.isAfter(MAX_CREATED_AT)) {
			throw invalid();
		}
		return new NoticeCursor(pinned, parsed, noticeId);
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_INPUT, INVALID_MESSAGE);
	}

}
