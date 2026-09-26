package com.reused.audit.dto.response;

import java.time.Instant;

import com.reused.audit.api.AuditResult;
import com.reused.user.dto.response.UserSummaryResponse;

/**
 * 감사 로그 한 건(감사 로그 조회 명세). {@code ip_address}와 {@code detail}은 싣지 않는다.
 *
 * @param actor 시스템 행위(actor_id NULL)는 null. 탈퇴한 행위자는 {@code 탈퇴회원#{userId}} 그대로
 * @param action 저장된 값 그대로. 과거에 기록된 값이 enum에서 빠져도 조회는 된다
 * @param targetType 대상이 없는 기록은 null
 * @param targetId 대상이 없는 기록은 null
 */
public record AuditLogResponse(
		Long auditLogId,
		UserSummaryResponse actor,
		String action,
		String targetType,
		Long targetId,
		AuditResult result,
		Instant createdAt) {
}
