package com.reused.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Pub/Sub only delivers committed state; REST history recovers missed realtime events. */
@Component
public class ChatEventPublisher {
	private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);
	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	public ChatEventPublisher(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }
	public void afterCommit(String event, Long roomId, Object data) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCommit() {
				try {
					redis.convertAndSend("reused:chat:room:" + roomId,
							mapper.writeValueAsString(new Envelope(event, roomId, data)));
				}
				catch (RuntimeException ex) {
					log.warn("Chat event delivery failed: room={}, event={}, cause={}", roomId, event,
							ex.getClass().getSimpleName());
				}
			}
		});
	}
	private record Envelope(String event, Long chatRoomId, Object data) {}
}
