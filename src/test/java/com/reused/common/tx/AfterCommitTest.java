package com.reused.common.tx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 동기화를 직접 켜고 끄며 커밋·롤백 시점을 흉내 낸다. 실제 트랜잭션 경로는 인증 통합 테스트가 검증한다.
 */
class AfterCommitTest {

	private final List<String> calls = new ArrayList<>();

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("트랜잭션 밖이면 즉시 실행한다")
	void runsImmediatelyWithoutTransaction() {
		AfterCommit.run(() -> calls.add("task"));

		assertThat(calls).containsExactly("task");
	}

	@Test
	@DisplayName("트랜잭션 안이면 커밋될 때까지 미루고, 등록한 순서대로 실행한다")
	void defersUntilCommit() {
		TransactionSynchronizationManager.initSynchronization();

		AfterCommit.run(() -> calls.add("first"));
		AfterCommit.run(() -> calls.add("second"));
		assertThat(calls).isEmpty();

		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		assertThat(calls).containsExactly("first", "second");
	}

	@Test
	@DisplayName("롤백되면 실행하지 않는다")
	void skippedOnRollback() {
		TransactionSynchronizationManager.initSynchronization();

		AfterCommit.run(() -> calls.add("task"));
		TransactionSynchronizationManager.getSynchronizations()
				.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		assertThat(calls).isEmpty();
	}

}
