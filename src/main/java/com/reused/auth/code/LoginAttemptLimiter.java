package com.reused.auth.code;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.reused.auth.config.LocalAuthProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 비밀번호 추측 속도 제한(NFR-AUTH-019). 계정 잠금이 아니라 요청 차단이며 창이 지나면 자동 해제된다.
 *
 * <p>키 {@code reused:auth:login-fail:{identityId}}, TTL 10분(redis-keys.md). 값은 마지막 성공 이후의 시도 수다.
 * 검증 전에 시도를 먼저 세므로 진행 중인 시도도 포함한다. 성공하면 호출자가 {@link #reset}한다.
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
	 * 비밀번호를 검증하기 전에 이번 시도를 센다. 창 안에서 한도를 넘는 시도는 검증하지 않고 거절한다.
	 *
	 * <p>횟수를 읽기만 하고 실패 뒤에 더하면, 해시 검증(수십 ms) 사이에 들어온 동시 요청이 모두 같은 값을 보고 통과해
	 * 창 하나에 한도보다 많이 추측할 수 있다. INCR 반환값으로 판정하면 동시 요청도 각자 다른 순번을 받는다.
	 * 거절된 시도도 세지만 TTL은 첫 증가에만 걸므로 창이 늘어나지는 않는다(conventions 고정 창 카운터).
	 *
	 * @throws BusinessException RATE_LIMITED 이번 시도가 창 안에서 한도를 넘는 경우
	 */
	public void acquire(Long identityId) {
		String key = KEY_PREFIX + identityId;
		Long count = redis.opsForValue().increment(key);
		if (count != null && count == 1L) {
			redis.expire(key, properties.loginFailWindow());
		}
		if (count != null && count > properties.loginFailLimit()) {
			throw new BusinessException(ErrorCode.RATE_LIMITED, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	public void reset(Long identityId) {
		redis.delete(KEY_PREFIX + identityId);
	}

}
