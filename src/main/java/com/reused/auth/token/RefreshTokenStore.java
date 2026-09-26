package com.reused.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditTargetType;
import com.reused.auth.config.AuthProperties;

/**
 * Refresh Token 화이트리스트(ADR-005, ADR-010).
 *
 * <p>화이트리스트를 쓰는 이유는 저장소 조회에 실패하면 거부 쪽으로 동작하기 때문이다.
 * 블랙리스트는 등록이 누락되면 통과해버린다.
 *
 * <p>토큰은 JWT가 아니라 불투명 값 {@code {userId}.{secret}}이다. 어차피 매 재발급마다 Redis를
 * 조회하므로 자기검증이 필요 없고, 쿠키가 작으며 클레임이 새지 않는다. userId를 토큰에 넣는 것은
 * 조회 키를 만들기 위함이며 비밀이 아니다. 비밀은 secret이고 Redis에는 그 SHA-256 해시만 둔다.
 *
 * <p>키는 04-data/redis-keys.md의 규약을 따른다.
 * <ul>
 *   <li>{@code reused:auth:refresh:{userId}:{tokenId}} — 유효한 토큰. TTL 14일
 *   <li>{@code reused:auth:refresh-used:{userId}:{tokenId}} — 회전으로 폐기된 토큰. 재사용 탐지용. TTL 14일
 * </ul>
 * userId가 tokenId보다 앞에 있어야 {@code SCAN reused:auth:refresh:{userId}:*}로 사용자 전체 폐기가 된다.
 */
@Component
public class RefreshTokenStore {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

	private static final String KEY_PREFIX = "reused:auth:refresh:";
	private static final String USED_KEY_PREFIX = "reused:auth:refresh-used:";
	private static final String SEPARATOR = ".";

	private final StringRedisTemplate redis;
	private final AuthProperties properties;
	private final AuditLogger auditLogger;
	private final SecureRandom random = new SecureRandom();

	public RefreshTokenStore(StringRedisTemplate redis, AuthProperties properties, AuditLogger auditLogger) {
		this.redis = redis;
		this.properties = properties;
		this.auditLogger = auditLogger;
	}

	public String issue(Long userId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redis.opsForValue().set(tokenKey(userId, hash(secret)), Instant.now().toString(), properties.refreshTokenTtl());
		return userId + SEPARATOR + secret;
	}

	/**
	 * 재발급. 기존 토큰을 폐기하고 새로 발급한다(Rotation).
	 *
	 * <p>이미 폐기된 토큰이 다시 오면 탈취로 보고 해당 사용자의 전체 토큰을 무효화한다(ADR-005).
	 *
	 * @throws InvalidTokenException 화이트리스트에 없는 토큰
	 */
	public Rotation rotate(String token) {
		ParsedToken parsed = parse(token);
		String key = tokenKey(parsed.userId(), parsed.tokenId());

		// DEL의 반환값으로 존재 여부와 삭제를 한 번에 처리해 동시 재발급 경쟁을 막는다.
		if (!Boolean.TRUE.equals(redis.delete(key))) {
			detectReuse(parsed);
			throw new InvalidTokenException("등록되지 않은 Refresh Token입니다.");
		}
		redis.opsForValue().set(usedKey(parsed.userId(), parsed.tokenId()), "1", properties.refreshTokenTtl());

		return new Rotation(parsed.userId(), issue(parsed.userId()));
	}

	/**
	 * 로그아웃. 화이트리스트에서 제거한다. 없는 토큰이거나 형식이 잘못되어도 조용히 넘어간다.
	 */
	public void revoke(String token) {
		try {
			ParsedToken parsed = parse(token);
			redis.delete(tokenKey(parsed.userId(), parsed.tokenId()));
		}
		catch (InvalidTokenException ignored) {
			// 로그아웃은 실패할 이유가 없다
		}
	}

	/**
	 * 해당 사용자의 모든 Refresh Token을 폐기한다.
	 * 탈퇴, 비밀번호 변경·재설정(NFR-AUTH-016), 토큰 재사용 탐지 시 호출한다.
	 *
	 * <p>운영에서 KEYS는 금지이므로 SCAN을 쓴다(redis-keys.md).
	 */
	public void revokeAll(Long userId) {
		List<String> keys = new ArrayList<>();
		ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + userId + ":*").count(200).build();
		try (Cursor<String> cursor = redis.scan(options)) {
			cursor.forEachRemaining(keys::add);
		}
		if (!keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	/**
	 * 재사용한 쪽이 탈취자인지 정상 사용자인지 알 수 없으므로 감사 기록의 행위자는 null이고 대상이 계정 주인이다.
	 */
	private void detectReuse(ParsedToken parsed) {
		if (Boolean.TRUE.equals(redis.hasKey(usedKey(parsed.userId(), parsed.tokenId())))) {
			log.warn("폐기된 Refresh Token 재사용 감지. 사용자 전체 토큰을 무효화합니다. userId={}", parsed.userId());
			revokeAll(parsed.userId());
			auditLogger.recordSeparately(AuditEntry.failure(AuditAction.AUTH_TOKEN_REUSE_DETECTED, null,
					AuditTargetType.USER, parsed.userId(), null));
		}
	}

	private ParsedToken parse(String token) {
		int separator = token == null ? -1 : token.indexOf(SEPARATOR);
		if (separator <= 0 || separator == token.length() - 1) {
			throw new InvalidTokenException("Refresh Token 형식이 올바르지 않습니다.");
		}
		try {
			Long userId = Long.valueOf(token.substring(0, separator));
			return new ParsedToken(userId, hash(token.substring(separator + 1)));
		}
		catch (NumberFormatException e) {
			throw new InvalidTokenException("Refresh Token 형식이 올바르지 않습니다.", e);
		}
	}

	private static String tokenKey(Long userId, String tokenId) {
		return KEY_PREFIX + userId + ":" + tokenId;
	}

	private static String usedKey(Long userId, String tokenId) {
		return USED_KEY_PREFIX + userId + ":" + tokenId;
	}

	private static String hash(String secret) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(secret.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}

	private record ParsedToken(Long userId, String tokenId) {
	}

	public record Rotation(Long userId, String refreshToken) {
	}

}
