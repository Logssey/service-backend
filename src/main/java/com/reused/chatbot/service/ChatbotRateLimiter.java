package com.reused.chatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.reused.chatbot.config.ChatbotProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 챗봇 호출 빈도 제한(FR-AI-006, NFR-EXT-003). 사용자당 고정 창이며 창이 지나면 키째 사라진다.
 * 여러 인스턴스가 같은 한도를 나눠 쓰도록 Redis에 센다(인스턴스 메모리로 세지 않는다).
 *
 * <p>키 {@code reused:chatbot:rate:{userId}}, Counter, TTL 1분(redis-keys.md). 거부된 호출도 카운트가 오른다.
 *
 * <p>Redis를 쓸 수 없으면 제한 없이 LLM을 부르지 않도록 닫힌 쪽(503)으로 처리한다. LLM 인증정보가 새거나
 * 호출이 몰리면 비용이 생기기 때문이다.
 */
@Component
public class ChatbotRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(ChatbotRateLimiter.class);

	static final String KEY_PREFIX = "reused:chatbot:rate:";

	/** {@code getExpire}가 돌려주는 "키는 있지만 TTL이 없음" */
	private static final long NO_EXPIRY = -1L;

	private final StringRedisTemplate redis;
	private final ChatbotProperties properties;

	public ChatbotRateLimiter(StringRedisTemplate redis, ChatbotProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * 호출 한 번을 센다.
	 *
	 * @throws BusinessException RATE_LIMITED 창 안에서 한도를 넘은 경우, SERVICE_UNAVAILABLE Redis 장애
	 */
	public void acquire(Long userId) {
		String key = KEY_PREFIX + userId;
		Long count;
		try {
			count = redis.opsForValue().increment(key);
			// INCR 뒤 EXPIRE 전에 프로세스가 죽으면 TTL 없는 키가 남아 영구 차단된다. 발견하면 다시 건다(redis-keys 규칙 3).
			if ((count != null && count == 1L) || isMissingExpiry(key)) {
				redis.expire(key, properties.rateWindow());
			}
		}
		catch (DataAccessException e) {
			log.warn("챗봇 호출 제한 확인 실패. userId={}, exception={}", userId, e.getClass().getSimpleName());
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "현재 챗봇을 이용할 수 없습니다.");
		}
		if (count != null && count > properties.rateLimit()) {
			throw new BusinessException(ErrorCode.RATE_LIMITED,
					"챗봇은 1분에 " + properties.rateLimit() + "번까지 이용할 수 있습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	private boolean isMissingExpiry(String key) {
		Long ttl = redis.getExpire(key);
		return ttl != null && ttl == NO_EXPIRY;
	}

}
