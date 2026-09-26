package com.reused.common.pagination;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 커서 페이지 요청(API 명세 §0.4). 쿼리 파라미터 {@code cursor}, {@code size}를 암묵적 {@code @ModelAttribute}로 받는다.
 *
 * <pre>
 * public CursorPageResponse&lt;XxxResponse&gt; list(&#64;Valid CursorPageRequest page) { ... }
 * </pre>
 *
 * <p>size가 1~100 밖이면 잘라내지 않고 400이다. 생략하면 {@link #DEFAULT_SIZE}.
 * cursor는 이전 응답의 {@code nextCursor}이며 {@link CursorCodec}으로 해석한다.
 */
public record CursorPageRequest(String cursor, @Min(1) @Max(100) Integer size) {

	public static final int DEFAULT_SIZE = 20;

	public int sizeOrDefault() {
		return size == null ? DEFAULT_SIZE : size;
	}

}
