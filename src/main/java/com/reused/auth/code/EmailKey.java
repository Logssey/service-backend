package com.reused.auth.code;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 가입되지 않은 이메일에 제한 카운터를 걸 때 쓰는 키 조각. 이메일 원문 대신 SHA-256 해시를 쓴다.
 *
 * <p>가입된 계정은 identityId로 세지만, 없는 계정에는 identityId가 없다. 이메일을 키에 그대로 넣으면
 * SLOWLOG·모니터링에 개인정보가 남는다(NFR-LOG-003).
 */
final class EmailKey {

	private EmailKey() {
	}

	/** 정규화한 이메일을 받는다. 대소문자가 다르면 다른 키가 되어 한도를 우회할 수 있다. */
	static String of(String normalizedEmail) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}
}
