package com.reused.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.reused.auth.config.AuthProperties;

/**
 * Refresh Token 화이트리스트(ADR-005, ADR-010).
 *
 * <p>화이트리스트를 쓰는 이유는 저장소 조회에 실패하면 거부 쪽으로 동작하기 때문이다.
 * 블랙리스트는 등록이 누락되면 통과해버린다.
 *
 * <p>토큰은 JWT가 아니라 불투명 난수다. 어차피 매 재발급마다 Redis를 조회하므로
 * 자기검증이 필요 없고, 쿠키 크기가 작으며 클레임이 새지 않는다.
 * Redis에는 토큰 원문이 아니라 SHA-256 해시를 키로 저장한다.
 *
 * <p>키 구조
 * <ul>
 *   <li>{@code auth:refresh:{hash}} → userId : 유효한 토큰
 *   <li>{@code auth:refresh:used:{hash}} → userId : 회전으로 폐기된 토큰. 재사용 탐지용
 *   <li>{@code auth:user-tokens:{userId}} → Set&lt;hash&gt; : 사용자 전체 폐기용 색인
 * </ul>
 */
@Component
public class RefreshTokenStore {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

	private static final String KEY_TOKEN = "auth:refresh:";
	private static final String KEY_USED = "auth:refresh:used:";
	private static final String KEY_USER_TOKENS = "auth:user-tokens:";

	private final StringRedisTemplate redis;
	private final AuthProperties properties;
	private final SecureRandom random = new SecureRandom();

	public RefreshTokenStore(StringRedisTemplate redis, AuthProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	public String issue(Long userId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		register(userId, token);
		return token;
	}

	/**
	 * 재발급. 기존 토큰을 폐기하고 새로 발급한다(Rotation).
	 *
	 * <p>이미 폐기된 토큰이 다시 오면 탈취로 보고 해당 사용자의 전체 토큰을 무효화한다(ADR-005).
	 *
	 * @throws InvalidTokenException 화이트리스트에 없는 토큰
	 */
	public Rotation rotate(String token) {
		String hash = hash(token);
		String userId = redis.opsForValue().get(KEY_TOKEN + hash);

		if (userId == null) {
			detectReuse(hash);
			throw new InvalidTokenException("등록되지 않은 Refresh Token입니다.");
		}

		revokeHash(userId, hash);
		redis.opsForValue().set(KEY_USED + hash, userId, properties.refreshTokenTtl());

		String newToken = issue(Long.valueOf(userId));
		return new Rotation(Long.valueOf(userId), newToken);
	}

	/**
	 * 로그아웃. 화이트리스트에서 제거한다. 없는 토큰이어도 조용히 넘어간다.
	 */
	public void revoke(String token) {
		String hash = hash(token);
		String userId = redis.opsForValue().get(KEY_TOKEN + hash);
		if (userId != null) {
			revokeHash(userId, hash);
		}
	}

	/**
	 * 해당 사용자의 모든 Refresh Token을 폐기한다.
	 * 탈퇴, 이용정지 전환, 토큰 재사용 탐지 시 호출한다.
	 */
	public void revokeAll(Long userId) {
		String indexKey = KEY_USER_TOKENS + userId;
		Set<String> hashes = redis.opsForSet().members(indexKey);
		if (hashes != null) {
			hashes.forEach(hash -> redis.delete(KEY_TOKEN + hash));
		}
		redis.delete(indexKey);
	}

	private void register(Long userId, String token) {
		String hash = hash(token);
		redis.opsForValue().set(KEY_TOKEN + hash, String.valueOf(userId), properties.refreshTokenTtl());
		String indexKey = KEY_USER_TOKENS + userId;
		redis.opsForSet().add(indexKey, hash);
		redis.expire(indexKey, properties.refreshTokenTtl());
	}

	private void revokeHash(String userId, String hash) {
		redis.delete(KEY_TOKEN + hash);
		redis.opsForSet().remove(KEY_USER_TOKENS + userId, hash);
	}

	private void detectReuse(String hash) {
		String userId = redis.opsForValue().get(KEY_USED + hash);
		if (userId != null) {
			log.warn("폐기된 Refresh Token 재사용 감지. 사용자 전체 토큰을 무효화합니다. userId={}", userId);
			revokeAll(Long.valueOf(userId));
		}
	}

	private String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}

	public record Rotation(Long userId, String refreshToken) {
	}

}
