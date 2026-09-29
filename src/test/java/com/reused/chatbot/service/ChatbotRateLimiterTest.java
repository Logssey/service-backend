package com.reused.chatbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.reused.chatbot.config.ChatbotProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * Redis 장애 처리. 정상 경로(횟수·TTL)는 실제 Redis로 {@code ChatbotIntegrationTest}가 확인한다.
 */
class ChatbotRateLimiterTest {

	@Test
	@DisplayName("Redis를 쓸 수 없으면 제한 없이 통과시키지 않고 503 SERVICE_UNAVAILABLE이다")
	@SuppressWarnings("unchecked")
	void redisFailureFailsClosed() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		given(redis.opsForValue()).willReturn(values);
		given(values.increment("reused:chatbot:rate:1")).willThrow(new RedisConnectionFailureException("down"));
		ChatbotProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
				.bindOrCreate("app.chatbot", ChatbotProperties.class);

		ChatbotRateLimiter limiter = new ChatbotRateLimiter(redis, properties);

		assertThatThrownBy(() -> limiter.acquire(1L))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).errorCode())
						.isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
	}

}
