package com.reused.auth.service;

import java.util.Map;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditTargetType;
import com.reused.user.entity.AuthProvider;

/**
 * 인증 이벤트 감사 기록의 모양을 한곳에 모은다(FR-LOG-001). 전부 {@code recordSeparately}로 남긴다.
 *
 * <p>detail에는 제공자와 실패 사유 코드만 둔다. 이메일·토큰·코드·비밀번호는 넣지 않는다(NFR-LOG-003).
 * 대상은 계정 주인(USER/userId)이다. 계정을 식별할 수 없는 실패는 행위자·대상이 모두 null이다.
 */
final class AuthAuditEntries {

	static final String REASON_UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
	static final String REASON_BAD_CREDENTIALS = "BAD_CREDENTIALS";
	static final String REASON_SUSPENDED = "SUSPENDED";
	static final String REASON_RATE_LIMITED = "RATE_LIMITED";
	static final String REASON_WITHDRAWN = "WITHDRAWN";

	private static final String KEY_PROVIDER = "provider";
	private static final String KEY_REASON = "reason";

	private AuthAuditEntries() {
	}

	static AuditEntry signedUp(Long userId, AuthProvider provider) {
		return AuditEntry.success(AuditAction.AUTH_SIGNUP, userId, AuditTargetType.USER, userId,
				Map.of(KEY_PROVIDER, provider.name()));
	}

	static AuditEntry loginSucceeded(Long userId, AuthProvider provider) {
		return AuditEntry.success(AuditAction.AUTH_LOGIN, userId, AuditTargetType.USER, userId,
				Map.of(KEY_PROVIDER, provider.name()));
	}

	/**
	 * @param userId 계정을 찾지 못했으면 null
	 */
	static AuditEntry loginFailed(Long userId, AuthProvider provider, String reason) {
		return AuditEntry.failure(AuditAction.AUTH_LOGIN, userId, userId == null ? null : AuditTargetType.USER, userId,
				Map.of(KEY_PROVIDER, provider.name(), KEY_REASON, reason));
	}

	static AuditEntry loggedOut(Long userId) {
		return AuditEntry.success(AuditAction.AUTH_LOGOUT, userId, AuditTargetType.USER, userId, null);
	}

	/**
	 * 재설정은 로그인하지 않은 상태에서 코드로 한다. 행위자를 특정할 수 없어 actor는 null이고 대상이 계정 주인이다.
	 */
	static AuditEntry passwordReset(Long userId) {
		return AuditEntry.success(AuditAction.AUTH_PASSWORD_RESET, null, AuditTargetType.USER, userId, null);
	}

	/**
	 * 비밀번호 변경은 로그인한 본인이 한다. 행위자와 대상이 같다.
	 */
	static AuditEntry passwordChanged(Long userId) {
		return AuditEntry.success(AuditAction.AUTH_PASSWORD_CHANGE, userId, AuditTargetType.USER, userId, null);
	}

	/**
	 * @param reason {@link #REASON_BAD_CREDENTIALS}(현재 비밀번호 불일치) 또는 {@link #REASON_RATE_LIMITED}
	 */
	static AuditEntry passwordChangeFailed(Long userId, String reason) {
		return AuditEntry.failure(AuditAction.AUTH_PASSWORD_CHANGE, userId, AuditTargetType.USER, userId,
				Map.of(KEY_REASON, reason));
	}

}
