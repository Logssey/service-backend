package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;

/**
 * User 엔티티의 상태 변경 메서드. 저장 매핑은 {@link UserPersistenceIntegrationTest}가 확인한다.
 */
class UserTest {

	private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

	@Test
	@DisplayName("ACTIVE 회원은 정지 중이 아니다")
	void activeUserIsNotSuspended() {
		User user = User.signUp("재현", NOW);

		assertThat(user.isSuspendedAt(NOW)).isFalse();
		assertThat(user.isSuspended()).isFalse();
	}

	@Test
	@DisplayName("기한 있는 정지는 종료 시각 전까지만 정지 중이다. 종료 시각 자체부터는 해제로 본다")
	void timedSuspensionEndsAtUntil() {
		User user = User.signUp("재현", NOW);
		Instant until = NOW.plus(Duration.ofDays(7));

		user.suspend(until, NOW);

		assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
		assertThat(user.getSuspendedUntil()).isEqualTo(until);
		assertThat(user.getUpdatedAt()).isEqualTo(NOW);
		assertThat(user.isSuspendedAt(until.minusMillis(1))).isTrue();
		assertThat(user.isSuspendedAt(until)).isFalse();
		assertThat(user.isSuspendedAt(until.plusSeconds(1))).isFalse();
		// DB 상태값만 보는 isSuspended는 기간과 무관하다
		assertThat(user.isSuspended()).isTrue();
	}

	@Test
	@DisplayName("종료 시각이 없는 정지는 무기한이다")
	void indefiniteSuspensionNeverEnds() {
		User user = User.signUp("재현", NOW);

		user.suspend(null, NOW);

		assertThat(user.isSuspendedAt(NOW.plus(Duration.ofDays(36500)))).isTrue();
	}

	@Test
	@DisplayName("해제하면 ACTIVE가 되고 종료 시각이 지워진다")
	void activateClearsSuspension() {
		User user = User.signUp("재현", NOW);
		user.suspend(NOW.plusSeconds(60), NOW);
		Instant later = NOW.plusSeconds(10);

		user.activate(later);

		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(user.getSuspendedUntil()).isNull();
		assertThat(user.getUpdatedAt()).isEqualTo(later);
		assertThat(user.isSuspendedAt(later)).isFalse();
	}

	@Test
	@DisplayName("changeRole은 역할과 수정 시각을 바꾼다")
	void changeRole() {
		User user = User.signUp("재현", NOW);

		user.changeRole(UserRole.ADMIN, NOW);

		assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
		assertThat(user.getUpdatedAt()).isEqualTo(NOW);
	}

}
