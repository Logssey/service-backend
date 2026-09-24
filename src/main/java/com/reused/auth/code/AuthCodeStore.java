package com.reused.auth.code;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.reused.auth.config.LocalAuthProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 이메일 소유 확인·비밀번호 재설정 코드와 발송 제한(ADR-016, NFR-AUTH-017).
 *
 * <p>키는 redis-keys.md를 따르며 식별자는 이메일이 아니라 identityId다. 이메일을 키에 넣으면
 * SLOWLOG·모니터링에 개인정보가 남는다(NFR-LOG-003).
 * <ul>
 *   <li>{@code reused:auth:{verify|reset}:{identityId}} — 코드. TTL 10분
 *   <li>{@code reused:auth:{verify|reset}-try:{identityId}} — 검증 시도 횟수. 코드와 같은 TTL
 *   <li>{@code reused:auth:resend:{identityId}} — 발송 횟수. TTL 1시간
 *   <li>{@code reused:auth:resend-gap:{identityId}} — 발송 간격 잠금. 존재 자체가 잠금. TTL 60초
 * </ul>
 *
 * <p>시도 카운터 TTL을 코드와 같게 두면 코드가 만료될 때 카운터도 사라져 "코드 하나당 N회"가 성립한다.
 */
@Component
public class AuthCodeStore {

	private static final String KEY_PREFIX = "reused:auth:";
	private static final int CODE_BOUND = 1_000_000;

	private final StringRedisTemplate redis;
	private final LocalAuthProperties properties;
	private final SecureRandom random = new SecureRandom();

	public AuthCodeStore(StringRedisTemplate redis, LocalAuthProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * 새 코드를 발급한다. 이전 코드와 시도 횟수는 폐기된다.
	 *
	 * <p>{@code java.util.Random}은 시드가 48비트여서 출력 관찰만으로 예측된다. 반드시 SecureRandom을 쓴다.
	 */
	public String issue(CodePurpose purpose, Long identityId) {
		String code = String.format("%06d", random.nextInt(CODE_BOUND));
		redis.opsForValue().set(codeKey(purpose, identityId), code, properties.codeTtl());
		redis.delete(tryKey(purpose, identityId));
		return code;
	}

	/**
	 * 코드를 검증하고 성공하면 폐기한다(1회 사용).
	 *
	 * <p>만료·재사용·불일치를 구분하지 않고 같은 오류로 응답한다(엔드포인트 명세).
	 * 코드당 시도가 한도를 넘으면 코드를 폐기하고 429를 낸다.
	 *
	 * @throws BusinessException INVALID_INPUT 또는 RATE_LIMITED
	 */
	public void consume(CodePurpose purpose, Long identityId, String code) {
		String codeKey = codeKey(purpose, identityId);
		String tryKey = tryKey(purpose, identityId);

		String stored = redis.opsForValue().get(codeKey);
		if (stored == null) {
			throw invalidCode();
		}

		Long attempts = redis.opsForValue().increment(tryKey);
		if (attempts != null && attempts == 1L) {
			redis.expire(tryKey, properties.codeTtl());
		}
		if (attempts != null && attempts > properties.codeMaxAttempts()) {
			redis.delete(codeKey);
			redis.delete(tryKey);
			throw new BusinessException(ErrorCode.RATE_LIMITED,
					"인증 코드 확인 횟수를 초과했습니다. 코드를 다시 요청해 주세요.");
		}

		if (code == null || !MessageDigest.isEqual(
				stored.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
			throw invalidCode();
		}

		redis.delete(codeKey);
		redis.delete(tryKey);
	}

	/**
	 * 발송 제한을 확인하고 이번 발송을 기록한다. 발송 직전에 호출한다.
	 *
	 * @throws BusinessException RATE_LIMITED 간격 또는 시간당 횟수 초과
	 */
	public void recordSend(Long identityId) {
		String gapKey = KEY_PREFIX + "resend-gap:" + identityId;
		String countKey = KEY_PREFIX + "resend:" + identityId;

		if (Boolean.TRUE.equals(redis.hasKey(gapKey))) {
			throw new BusinessException(ErrorCode.RATE_LIMITED,
					"인증 메일은 " + properties.resendGap().toSeconds() + "초 후에 다시 요청할 수 있습니다.");
		}

		Long count = redis.opsForValue().increment(countKey);
		if (count != null && count == 1L) {
			redis.expire(countKey, properties.resendWindow());
		}
		if (count != null && count > properties.resendLimit()) {
			throw new BusinessException(ErrorCode.RATE_LIMITED,
					"인증 메일 발송 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요.");
		}

		redis.opsForValue().set(gapKey, "1", properties.resendGap());
	}

	private static BusinessException invalidCode() {
		return new BusinessException(ErrorCode.INVALID_INPUT, "인증 코드가 올바르지 않거나 만료되었습니다.");
	}

	private static String codeKey(CodePurpose purpose, Long identityId) {
		return KEY_PREFIX + purpose.segment() + ":" + identityId;
	}

	private static String tryKey(CodePurpose purpose, Long identityId) {
		return KEY_PREFIX + purpose.segment() + "-try:" + identityId;
	}

}
