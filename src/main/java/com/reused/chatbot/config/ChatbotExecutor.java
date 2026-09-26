package com.reused.chatbot.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/**
 * 챗봇 전용 스레드 풀(ADR-003, NFR-EXT-001). LLM이 느려져도 요청 스레드(Tomcat)를 모두 붙잡지 못하게
 * 동시에 기다리는 호출 수를 풀 크기 + 큐 길이로 묶는다. 가득 차면 기다리지 않고 거절한다.
 *
 * <p>{@link java.util.concurrent.Executor} 타입 빈으로 등록하지 않는다. 그런 빈이 있으면 Spring Boot의
 * {@code applicationTaskExecutor} 자동 구성이 물러나 다른 비동기 작업이 이 풀로 들어올 수 있다.
 */
@Component
public class ChatbotExecutor {

	private static final long KEEP_ALIVE_SECONDS = 60;

	private final ThreadPoolExecutor pool;

	public ChatbotExecutor(ChatbotProperties properties) {
		ChatbotProperties.ExecutorSettings settings = properties.executor();
		AtomicInteger sequence = new AtomicInteger();
		this.pool = new ThreadPoolExecutor(settings.poolSize(), settings.poolSize(),
				KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(settings.queueCapacity()),
				task -> {
					Thread thread = new Thread(task, "chatbot-" + sequence.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				},
				new ThreadPoolExecutor.AbortPolicy());
		// 호출이 드물어(ADR-003) 쉬는 동안 스레드를 붙잡아 두지 않는다.
		this.pool.allowCoreThreadTimeOut(true);
	}

	/**
	 * @throws RejectedExecutionException 실행 중인 작업과 대기열이 모두 가득 찬 경우
	 */
	public <T> Future<T> submit(Callable<T> task) {
		return pool.submit(task);
	}

	@PreDestroy
	void shutdown() {
		pool.shutdownNow();
	}

}
