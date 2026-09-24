package com.reused.auth.code;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.reused.auth.config.LocalAuthProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 로그인 실패 속도 제한(NFR-AUTH-019). 계정 잠금이 아니라 요청 차단이며 창이 지나면 자동 해제된다.
 *
 * <p>키 {@code reused:auth:login-fail:{identityId}}, TTL 10분(redis-keys.md).
 * 존재하지 않는 이메일은 identityId가 없어 세지 않는다. 그래도 응답은 계정이 있을 때와 같아야 한다(NFR-AUTH-018).
 */
@Component
public class LoginAttemptLimiter {

	private static final String KEY_PREFIX = "reused:auth:login-fail:";

	private final StringRedisTemplate redis;
	private final LocalAuthProperties properties;

	public LoginAttemptLimiter(StringRedisTemplate redis, LocalAuthProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * @throws BusinessException RATE_LIMITED 실패 횟수가 한도에 도달한 경우
	 */
	public void checkAllowed(Long identityId) {
		String value = redis.opsForValue().get(KEY_PREFIX + identityId);
		if (value != null && Long.parseLong(value) >= properties.loginFailLimit()) {
			throw new BusinessException(ErrorCode.RATE_LIMITED, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	public void recordFailure(Long identityId) {
		String key = KEY_PREFIX + identityId;
		Long count = redis.opsForValue().increment(key);
		if (count != null && count == 1L) {
			redis.expire(key, properties.loginFailWindow());
		}
	}

	public void reset(Long identityId) {
		redis.delete(KEY_PREFIX + identityId);
	}

}
