package com.reused.user.entity;

/**
 * 인증 수단 제공자. DB CHECK 제약(user_identities.provider)과 값이 일치해야 한다.
 *
 * <ul>
 *   <li>{@code KAKAO} — 소셜 로그인. 식별자는 카카오 회원번호이며 이메일·비밀번호를 저장하지 않는다(ADR-004, ADR-016)
 *   <li>{@code LOCAL} — 자체 이메일·비밀번호 계정. 식별자는 서버가 발급한 UUID다(ADR-016)
 * </ul>
 */
public enum AuthProvider {
	KAKAO,
	LOCAL
}
