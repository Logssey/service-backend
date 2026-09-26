package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;
import com.reused.user.entity.UserStatusHistory;
import com.reused.user.repository.UserRepository;
import com.reused.user.repository.UserStatusHistoryRepository;

/**
 * ddl-auto=none이라 매핑 오류는 첫 쿼리에서야 드러난다. 새 엔티티 메서드와 리포지토리가 실제 스키마에 맞는지 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserPersistenceIntegrationTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserStatusHistoryRepository historyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private TransactionTemplate tx;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		tx = new TransactionTemplate(transactionManager);
	}

	@Test
	@DisplayName("역할·정지 변경이 컬럼에 저장된다")
	void profileRoleAndSuspensionArePersisted() {
		long userId = insertUser("재현");
		Instant until = Instant.parse("2030-01-01T00:00:00Z");

		tx.executeWithoutResult(status -> {
			User user = userRepository.findById(userId).orElseThrow();
			user.changeRole(UserRole.ADMIN, Instant.now());
			user.suspend(until, Instant.now());
		});

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT role, status, suspended_until, updated_at FROM users "
						+ "WHERE user_id = ?", userId);
		assertThat(row.get("role")).isEqualTo("ADMIN");
		assertThat(row.get("status")).isEqualTo("SUSPENDED");
		assertThat(((java.sql.Timestamp) row.get("suspended_until")).toInstant()).isEqualTo(until);
		assertThat(row.get("updated_at")).isNotNull();

		tx.executeWithoutResult(status -> {
			User user = userRepository.findById(userId).orElseThrow();
			user.activate(Instant.now());
		});

		Map<String, Object> after = jdbcTemplate.queryForMap(
				"SELECT status, suspended_until FROM users WHERE user_id = ?", userId);
		assertThat(after.get("status")).isEqualTo("ACTIVE");
		assertThat(after.get("suspended_until")).isNull();
	}

	@Test
	@DisplayName("상태·역할 이력이 user_status_histories에 저장된다. 시스템 처리는 changed_by가 NULL이다")
	void historiesArePersisted() {
		long adminId = insertUser("관리자");
		long userId = insertUser("재현");

		historyRepository.save(UserStatusHistory.status(userId, UserStatus.ACTIVE, UserStatus.SUSPENDED, "욕설", adminId));
		historyRepository.save(UserStatusHistory.role(userId, UserRole.USER, UserRole.ADMIN, "운영 인력", adminId));
		historyRepository.save(UserStatusHistory.status(userId, UserStatus.SUSPENDED, UserStatus.ACTIVE, "정지 기간 만료",
				null));

		var rows = jdbcTemplate.queryForList("SELECT user_id, change_type, before_value, after_value, reason, "
				+ "changed_by, created_at FROM user_status_histories ORDER BY history_id");
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0)).containsEntry("user_id", userId).containsEntry("change_type", "STATUS")
				.containsEntry("before_value", "ACTIVE").containsEntry("after_value", "SUSPENDED")
				.containsEntry("reason", "욕설").containsEntry("changed_by", adminId);
		assertThat(rows.get(1)).containsEntry("change_type", "ROLE").containsEntry("before_value", "USER")
				.containsEntry("after_value", "ADMIN");
		assertThat(rows.get(2).get("changed_by")).isNull();
		assertThat(rows).allSatisfy(row -> assertThat(row.get("created_at")).isNotNull());
	}

	@Test
	@DisplayName("findByIdForUpdate는 행을 잠가 다른 트랜잭션의 FOR UPDATE NOWAIT가 실패한다")
	void findByIdForUpdateLocksRow() {
		long userId = insertUser("재현");

		tx.executeWithoutResult(status -> {
			assertThat(userRepository.findByIdForUpdate(userId)).isPresent();

			// 다른 스레드는 다른 커넥션을 쓴다
			CompletableFuture<Object> other = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForObject(
					"SELECT user_id FROM users WHERE user_id = ? FOR UPDATE NOWAIT", Long.class, userId));
			assertThatThrownBy(() -> other.get(10, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(DataAccessException.class);

			assertThat(userRepository.findByIdForUpdate(999L)).isEmpty();
		});
	}

	private long insertUser(String nickname) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
	}

}
