package com.reused.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시(NFR-AUTH-014).
 *
 * <p>business-rules 정책 값 그대로다 — argon2id, 메모리 19456 KiB, 반복 2, 병렬성 1,
 * Salt 16바이트, 해시 32바이트(OWASP Password Storage Cheat Sheet 권장 최소 구성).
 * 결과 문자열은 파라미터와 Salt를 포함하므로 나중에 값을 올려도 기존 해시 검증에 영향이 없다.
 */
@Configuration
public class PasswordEncoderConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
	}

}
