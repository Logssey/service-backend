package com.reused.auth.code;

/**
 * 인증 코드의 용도. Redis 키의 용도 세그먼트로 쓰인다(redis-keys.md).
 */
public enum CodePurpose {

	/** 이메일 소유 확인 — {@code reused:auth:verify:{identityId}} */
	VERIFY("verify"),

	/** 비밀번호 재설정 — {@code reused:auth:reset:{identityId}} */
	RESET("reset");

	private final String segment;

	CodePurpose(String segment) {
		this.segment = segment;
	}

	String segment() {
		return segment;
	}

}
