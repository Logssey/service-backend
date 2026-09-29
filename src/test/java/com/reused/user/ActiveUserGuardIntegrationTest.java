package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.api.ActiveUserGuard;

/**
 * 토큰 사용자의 DB 상태 확인. 외부 대역 구성은 인증 통합 테스트와 같게 두어 컨텍스트를 함께 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ActiveUserGuardIntegrationTest {

	private static final long MISSING_USER_ID = 999L;

	@Autowired
	private ActiveUserGuard guard;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
	}

	// --- requireActive ---

	@Test
	@DisplayName("ACTIVE 회원은 requireActive를 통과하고 isActive가 true다")
	void activeUserPasses() {
		long userId = insertUser("재현", "status = 'ACTIVE'");

		assertThatCode(() -> guard.requireActive(userId)).doesNotThrowAnyException();
		assertThat(guard.isActive(userId)).isTrue();
	}

	@Test
	@DisplayName("없는 회원과 null id는 401 UNAUTHENTICATED다")
	void missingUserIsUnauthenticated() {
		assertThatThrownBy(() -> guard.requireActive(MISSING_USER_ID)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
		assertThatThrownBy(() -> guard.requireActive(null)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
		assertThat(guard.isActive(MISSING_USER_ID)).isFalse();
		assertThat(guard.isActive(null)).isFalse();
	}

	@Test
	@DisplayName("탈퇴 회원은 상태값이든 탈퇴 시각이든 하나만 있어도 401이다")
	void withdrawnUserIsUnauthenticated() {
		long byStatus = insertUser("탈퇴회원#1", "status = 'WITHDRAWN', withdrawn_at = now()");
		long byTimestamp = insertUser("탈퇴시각만", "withdrawn_at = now()");

		assertThatThrownBy(() -> guard.requireActive(byStatus)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
		assertThatThrownBy(() -> guard.requireActive(byTimestamp)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
		assertThat(guard.isActive(byStatus)).isFalse();
		assertThat(guard.isActive(byTimestamp)).isFalse();
	}

	@Test
	@DisplayName("기한이 남은 정지와 무기한 정지는 403 USER_SUSPENDED다")
	void suspendedUserIsForbidden() {
		long timed = insertUser("기한정지", "status = 'SUSPENDED', suspended_until = now() + interval '1 day'");
		long indefinite = insertUser("무기한정지", "status = 'SUSPENDED', suspended_until = NULL");

		assertThatThrownBy(() -> guard.requireActive(timed)).satisfies(hasCode(ErrorCode.USER_SUSPENDED));
		assertThatThrownBy(() -> guard.requireActive(indefinite)).satisfies(hasCode(ErrorCode.USER_SUSPENDED));
		assertThat(guard.isActive(timed)).isFalse();
		assertThat(guard.isActive(indefinite)).isFalse();
	}

	@Test
	@DisplayName("기간이 지난 정지는 상태값이 아직 SUSPENDED여도 통과한다")
	void expiredSuspensionPasses() {
		long userId = insertUser("만료정지", "status = 'SUSPENDED', suspended_until = now() - interval '1 minute'");

		assertThatCode(() -> guard.requireActive(userId)).doesNotThrowAnyException();
		assertThat(guard.isActive(userId)).isTrue();
	}

	// --- requireMember ---

	@Test
	@DisplayName("requireMember는 정지 회원(기한·무기한·만료)을 통과시킨다")
	void requireMemberLetsSuspendedThrough() {
		long timed = insertUser("기한정지", "status = 'SUSPENDED', suspended_until = now() + interval '1 day'");
		long indefinite = insertUser("무기한정지", "status = 'SUSPENDED', suspended_until = NULL");
		long expired = insertUser("만료정지", "status = 'SUSPENDED', suspended_until = now() - interval '1 minute'");
		long active = insertUser("재현", "status = 'ACTIVE'");

		assertThatCode(() -> {
			guard.requireMember(timed);
			guard.requireMember(indefinite);
			guard.requireMember(expired);
			guard.requireMember(active);
		}).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("requireMember도 없음·탈퇴는 401이다")
	void requireMemberRejectsMissingAndWithdrawn() {
		long withdrawn = insertUser("탈퇴회원#1", "status = 'WITHDRAWN', withdrawn_at = now()");

		assertThatThrownBy(() -> guard.requireMember(withdrawn)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
		assertThatThrownBy(() -> guard.requireMember(MISSING_USER_ID)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
		assertThatThrownBy(() -> guard.requireMember(null)).satisfies(hasCode(ErrorCode.UNAUTHENTICATED));
	}

	// --- helpers ---

	/**
	 * @param state users 행에 바로 적용할 SET 절
	 */
	private long insertUser(String nickname, String state) {
		Long userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
		jdbcTemplate.update("UPDATE users SET " + state + " WHERE user_id = ?", userId);
		return userId;
	}

	private static ThrowingConsumer<Throwable> hasCode(ErrorCode code) {
		return thrown -> assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.errorCode()).isEqualTo(code));
	}

}
