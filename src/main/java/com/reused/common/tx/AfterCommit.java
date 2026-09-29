package com.reused.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤 실행한다. 롤백된 가입에 메일이 나가거나, 커밋되지 않은 변경보다
 * Redis 부수효과(토큰 폐기 등)가 먼저 일어나는 것을 막는다. 트랜잭션 밖이면 즉시 실행한다.
 *
 * <p>커밋 뒤에는 원래 트랜잭션의 자원이 아직 묶여 있다. 여기서 DB에 쓰려면
 * {@code REQUIRES_NEW}로 새 트랜잭션을 열어야 한다(Spring {@link TransactionSynchronization#afterCommit()} 문서).
 * 그러면 요청 하나가 커넥션을 두 개 잡으므로, 요청이 몰릴 수 있는 경로는 트랜잭션을 좁혀 끝낸 뒤 이 메서드를 부른다.
 * 그때는 트랜잭션이 없어 바로 실행된다.
 * 작업이 던진 예외는 호출자에게 전파되므로, 실패해도 되는 작업은 스스로 예외를 삼켜야 한다.
 */
public final class AfterCommit {

	private AfterCommit() {
	}

	public static void run(Runnable task) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			task.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				task.run();
			}
		});
	}

}
