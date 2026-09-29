package com.reused.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 같은 시도를 스레드마다 한 번씩 동시에 시작한다. 속도 제한이 동시 요청에도 한도를 지키는지 확인할 때 쓴다.
 *
 * <p>출발 간격이 해시 검증(수십 ms)보다 훨씬 짧아, 검증 뒤에 세는 구현이면 여러 건이 같은 횟수를 보고 통과한다.
 */
public final class ConcurrentAttempts {

	private ConcurrentAttempts() {
	}

	/**
	 * @return 시도마다의 오류 코드. 성공한 시도는 null이다.
	 */
	public static List<ErrorCode> run(int count, Runnable attempt) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CyclicBarrier start = new CyclicBarrier(count);
		try {
			List<CompletableFuture<ErrorCode>> futures = IntStream.range(0, count)
					.mapToObj(i -> CompletableFuture.<ErrorCode>supplyAsync(() -> {
						try {
							start.await(10, TimeUnit.SECONDS);
							attempt.run();
							return null;
						}
						catch (BusinessException e) {
							return e.errorCode();
						}
						catch (Exception e) {
							throw new IllegalStateException(e);
						}
					}, pool))
					.toList();
			List<ErrorCode> outcomes = new ArrayList<>();
			for (CompletableFuture<ErrorCode> future : futures) {
				outcomes.add(future.get(60, TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
