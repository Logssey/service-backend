package com.reused.audit.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 감사 로그 한 건. 시각과 IP는 기록기가 채운다.
 *
 * <p>detail에 <b>넣지 않는 것</b>: 이메일, 닉네임, 토큰, 인증·재설정 코드, 비밀번호, 신고 본문, 공지 본문(NFR-LOG-003).
 * <b>넣는 것</b>: provider, before/after, suspendedUntil, 관리자가 입력한 사유({@code reason}), reportId,
 * 변경된 필드 이름 목록, 외부 HTTP 상태.
 *
 * @param actorId 행위자. 시스템 행위이거나 식별할 수 없으면 null
 * @param targetType targetId가 있으면 필수
 * @param detail null이면 빈 맵으로 바꾼다. 값에 null을 담을 수 있다
 */
public record AuditEntry(AuditAction action, Long actorId, AuditTargetType targetType, Long targetId,
		AuditResult result, Map<String, Object> detail) {

	public AuditEntry {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(result, "result");
		if (targetId != null && targetType == null) {
			throw new IllegalArgumentException("targetId가 있으면 targetType도 있어야 한다.");
		}
		detail = (detail == null || detail.isEmpty())
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(detail));
	}

	public static AuditEntry success(AuditAction action, Long actorId, AuditTargetType targetType, Long targetId,
			Map<String, Object> detail) {
		return new AuditEntry(action, actorId, targetType, targetId, AuditResult.SUCCESS, detail);
	}

	public static AuditEntry failure(AuditAction action, Long actorId, AuditTargetType targetType, Long targetId,
			Map<String, Object> detail) {
		return new AuditEntry(action, actorId, targetType, targetId, AuditResult.FAILURE, detail);
	}

}
