package com.reused.common.pagination;

import java.util.List;
import java.util.function.Function;

/**
 * 커서 페이지 응답(API 명세 §0.2). 전체 개수·페이지 번호는 두지 않는다(ADR-013).
 *
 * <p>조회는 size+1건을 읽어 초과분으로 다음 페이지 존재를 판단한다.
 *
 * <pre>
 * List&lt;Row&gt; rows = repository.findPage(cursorId, size + 1);
 * return CursorPageResponse.of(rows, size, row -&gt; CursorCodec.encodeId(row.getId())).map(XxxResponse::from);
 * </pre>
 */
public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasNext) {

	public CursorPageResponse {
		items = List.copyOf(items);
	}

	public static <T> CursorPageResponse<T> empty() {
		return new CursorPageResponse<>(List.of(), null, false);
	}

	/**
	 * @param rows size+1개까지 읽은 결과. 초과분이 있으면 잘라내고 hasNext=true, nextCursor=cursorOf(마지막 항목)
	 * @param size 요청한 페이지 크기(1 이상)
	 * @param cursorOf 항목에서 다음 페이지 커서를 만든다
	 */
	public static <T> CursorPageResponse<T> of(List<T> rows, int size, Function<T, String> cursorOf) {
		if (size < 1) {
			throw new IllegalArgumentException("size는 1 이상이어야 한다: " + size);
		}
		if (rows.size() <= size) {
			return new CursorPageResponse<>(rows, null, false);
		}
		List<T> items = rows.subList(0, size);
		return new CursorPageResponse<>(items, cursorOf.apply(items.get(size - 1)), true);
	}

	/**
	 * 커서와 hasNext는 그대로 두고 항목만 바꾼다. 엔티티 → 응답 DTO 변환에 쓴다.
	 */
	public <R> CursorPageResponse<R> map(Function<T, R> mapper) {
		return new CursorPageResponse<>(items.stream().map(mapper).toList(), nextCursor, hasNext);
	}

}
