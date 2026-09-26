package com.reused.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

class CursorCodecTest {

	@Test
	@DisplayName("ID 커서는 {\"id\":N}의 Base64URL(패딩 없음)이고 그대로 되돌아온다")
	void idRoundTrip() {
		String cursor = CursorCodec.encodeId(123L);

		assertThat(cursor).isEqualTo("eyJpZCI6MTIzfQ");
		assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)).isEqualTo("{\"id\":123}");
		assertThat(CursorCodec.decodeId(cursor)).isEqualTo(123L);
		assertThat(CursorCodec.decodeId(CursorCodec.encodeId(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	@DisplayName("문서 예시의 표준 Base64(패딩 포함) eyJpZCI6MTIzfQ==는 123이다")
	void standardBase64WithPaddingIsAccepted() {
		assertThat(CursorCodec.decodeId("eyJpZCI6MTIzfQ==")).isEqualTo(123L);
	}

	@Test
	@DisplayName("표준 Base64의 +와 /도 받는다. 인코딩 없이 온 +가 공백으로 바뀌어도 받는다")
	void standardAlphabetIsAccepted() {
		// 이 JSON의 표준 Base64에는 +가 들어 있다(URL-safe로는 -)
		Map<String, Object> keys = new LinkedHashMap<>();
		keys.put("id", 1);
		keys.put("k", "?>~");
		String json = "{\"id\":1,\"k\":\"?>~\"}";
		String standard = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		assertThat(standard).containsAnyOf("+", "/");

		assertThat(CursorCodec.decode(standard)).isEqualTo(keys);
		assertThat(CursorCodec.decode(standard.replace('+', ' '))).isEqualTo(keys);
	}

	@Test
	@DisplayName("null이나 빈 커서는 첫 페이지(null)다")
	void nullOrBlankMeansFirstPage() {
		assertThat(CursorCodec.decodeId(null)).isNull();
		assertThat(CursorCodec.decodeId("")).isNull();
		assertThat(CursorCodec.decodeId("  ")).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"garbage!!",            // Base64 아님
			"bm90LWpzb24",          // "not-json"
			"WzEsMl0",              // [1,2] — 객체가 아님
			"e30",                  // {} — 키 없음
			"eyJpZCI6ImFiYyJ9",     // {"id":"abc"}
			"eyJpZCI6MS41fQ",       // {"id":1.5}
			"eyJpZCI6MH0",          // {"id":0}
			"eyJpZCI6LTF9",         // {"id":-1}
			"eyJpZCI6OTk5OTk5OTk5OTk5OTk5OTk5OTk5fQ", // long 범위 초과
			"eyJvdGhlciI6MX0",      // {"other":1}
			"eyJpZCI6MSwieCI6Mn0",  // {"id":1,"x":2} — 정렬 조건이 다른 커서
			"eyJpZCI6bnVsbH0"       // {"id":null}
	})
	@DisplayName("해석할 수 없는 ID 커서는 전부 400 INVALID_INPUT이다")
	void invalidIdCursorIsRejected(String cursor) {
		assertThatThrownBy(() -> CursorCodec.decodeId(cursor))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
					assertThat(e.getMessage()).isEqualTo("커서가 올바르지 않습니다.");
				});
	}

	@Test
	@DisplayName("비정상적으로 긴 커서는 디코딩하지 않고 거절한다")
	void tooLongCursorIsRejected() {
		assertThatThrownBy(() -> CursorCodec.decode("A".repeat(2000)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("복합 키 커서는 키 순서와 무관하게 같은 문자열이고 String·Number·Boolean을 그대로 되돌린다")
	void compositeRoundTrip() {
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("pinned", true);
		first.put("createdAt", "2026-03-15T09:30:00Z");
		first.put("id", 42);
		Map<String, Object> reordered = new LinkedHashMap<>();
		reordered.put("id", 42);
		reordered.put("createdAt", "2026-03-15T09:30:00Z");
		reordered.put("pinned", true);

		String cursor = CursorCodec.encode(first);

		assertThat(CursorCodec.encode(reordered)).isEqualTo(cursor);
		assertThat(cursor).doesNotContain("=", "+", "/");
		Map<String, Object> decoded = CursorCodec.decode(cursor);
		assertThat(decoded).containsEntry("pinned", true)
				.containsEntry("createdAt", "2026-03-15T09:30:00Z")
				.containsEntry("id", 42)
				.hasSize(3);
	}

	@Test
	@DisplayName("복합 키 커서에 객체·배열·null 값이 있으면 400이다")
	void compositeWithNonScalarIsRejected() {
		String nested = Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"id\":1,\"k\":{\"a\":1}}".getBytes(StandardCharsets.UTF_8));
		String array = Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"id\":[1]}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> CursorCodec.decode(nested)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> CursorCodec.decode(array)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> CursorCodec.decode(null)).isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("인코딩할 값이 String·Number·Boolean이 아니면 프로그래밍 오류다")
	void encodeRejectsUnsupportedValues() {
		assertThatThrownBy(() -> CursorCodec.encode(Map.of("id", new Object())))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CursorCodec.encode(Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
