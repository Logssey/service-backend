package com.reused.audit.dto.request;

import java.time.Instant;

import com.reused.audit.api.AuditAction;
import com.reused.common.pagination.CursorPageRequest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/**
 * 감사 로그 검색 조건(쿼리 파라미터). 암묵적 {@code @ModelAttribute}로 받는다.
 * action이 {@link AuditAction}에 없는 값이거나 시각이 ISO-8601이 아니면 바인딩 오류(typeMismatch)로 400이다.
 *
 * @param action 정확히 일치
 * @param from {@code created_at >= from}
 * @param to {@code created_at <= to}(명세 예시 {@code 23:59:59Z}가 끝값을 포함하는 것으로 읽힌다). from보다 이르면 400
 * @param size 기본 {@link CursorPageRequest#DEFAULT_SIZE}, 1~100 밖이면 400
 */
public record AuditLogSearchRequest(
		AuditAction action,
		@Positive Long actorId,
		Instant from,
		Instant to,
		String cursor,
		@Min(1) @Max(100) Integer size) {

	public int sizeOrDefault() {
		return size == null ? CursorPageRequest.DEFAULT_SIZE : size;
	}

}
