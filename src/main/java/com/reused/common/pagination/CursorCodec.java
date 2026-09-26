package com.reused.common.pagination;

import java.math.BigInteger;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 커서 인코딩. 페이로드는 정렬 키의 JSON이고 Base64URL(패딩 없음)로 감싼다(ADR-013 "서버만 해석 가능한 형태").
 *
 * <p>ID 내림차순 목록은 {@code {"id":N}} 하나로 충분하다. 문서 예시 {@code eyJpZCI6MTIzfQ==}가
 * 이 형식의 표준 Base64라서, 해석할 때는 표준·URL-safe, 패딩 유무를 모두 받는다.
 *
 * <p>해석할 수 없는 커서는 전부 같은 400이다. 무엇이 틀렸는지는 알려주지 않는다.
 */
public final class CursorCodec {

	static final String INVALID_MESSAGE = "커서가 올바르지 않습니다.";

	private static final String ID_KEY = "id";
	/** 정상 커서는 수십 자다. 비정상적으로 긴 입력은 디코딩하지 않고 거절한다. */
	private static final int MAX_LENGTH = 1024;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private CursorCodec() {
	}

	/**
	 * {@code {"id":N}} → Base64URL(패딩 없음)
	 */
	public static String encodeId(long id) {
		return encode(Map.of(ID_KEY, id));
	}

	/**
	 * @return cursor가 null이거나 비어 있으면 null(첫 페이지)
	 * @throws BusinessException INVALID_INPUT 해석할 수 없거나 {@code {"id":양의 정수}} 형태가 아닌 경우
	 */
	public static Long decodeId(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		Map<String, Object> keys = decode(cursor);
		if (keys.size() != 1 || !(keys.get(ID_KEY) instanceof Number number)) {
			throw invalid();
		}
		long id = toLong(number);
		if (id < 1) {
			throw invalid();
		}
		return id;
	}

	/**
	 * 복합 정렬 키용. 키 순서와 무관하게 같은 값이면 같은 커서가 나오도록 키 이름순으로 직렬화한다.
	 * 시각은 {@code Instant.toString()} 문자열로 넣고, 고유 키(PK)를 마지막 타이브레이커로 포함한다.
	 *
	 * @param keys 값은 String·Number·Boolean만. 그 밖의 값은 프로그래밍 오류다
	 */
	public static String encode(Map<String, ?> keys) {
		if (keys == null || keys.isEmpty()) {
			throw new IllegalArgumentException("커서 키가 비어 있다.");
		}
		Map<String, Object> sorted = new TreeMap<>();
		keys.forEach((key, value) -> {
			if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
				throw new IllegalArgumentException("커서 값은 String, Number, Boolean만 허용한다: " + key);
			}
			sorted.put(key, value);
		});
		byte[] json = MAPPER.writeValueAsBytes(sorted);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
	}

	/**
	 * @return 키 → 값(String·Number·Boolean). 수정할 수 없는 맵
	 * @throws BusinessException INVALID_INPUT null, Base64·JSON 오류, 객체가 아닌 JSON, 허용하지 않는 값 타입
	 */
	public static Map<String, Object> decode(String cursor) {
		if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
			throw invalid();
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(base64Decode(cursor));
		}
		catch (IllegalArgumentException | JacksonException e) {
			throw invalid(e);
		}
		if (root == null || !root.isObject() || root.isEmpty()) {
			throw invalid();
		}
		Map<String, Object> keys = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : root.properties()) {
			keys.put(entry.getKey(), scalar(entry.getValue()));
		}
		return Collections.unmodifiableMap(keys);
	}

	/**
	 * 표준 Base64를 URL-safe로 바꾸고 패딩을 떼어 한 가지 디코더로 읽는다.
	 * 쿼리 문자열에서 인코딩 없이 온 '+'는 공백으로 바뀌어 도착하므로 되돌린다.
	 */
	private static byte[] base64Decode(String cursor) {
		String normalized = cursor
				.replace(' ', '-')
				.replace('+', '-')
				.replace('/', '_');
		int end = normalized.length();
		while (end > 0 && normalized.charAt(end - 1) == '=') {
			end--;
		}
		return Base64.getUrlDecoder().decode(normalized.substring(0, end));
	}

	private static Object scalar(JsonNode node) {
		if (node.isString()) {
			return node.asString();
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isNumber()) {
			return node.numberValue();
		}
		throw invalid();
	}

	private static long toLong(Number number) {
		if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
			return number.longValue();
		}
		if (number instanceof BigInteger big && big.bitLength() < Long.SIZE) {
			return big.longValue();
		}
		throw invalid();
	}

	private static BusinessException invalid() {
		return new BusinessException(ErrorCode.INVALID_INPUT, INVALID_MESSAGE);
	}

	private static BusinessException invalid(Throwable cause) {
		return new BusinessException(ErrorCode.INVALID_INPUT, INVALID_MESSAGE, cause);
	}

}
