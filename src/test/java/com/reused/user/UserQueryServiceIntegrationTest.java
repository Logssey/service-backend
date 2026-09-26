package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import com.reused.user.api.UserQueryService;
import com.reused.user.api.UserSnapshot;
import com.reused.user.dto.response.UserSummaryResponse;
import com.reused.user.entity.UserStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserQueryServiceIntegrationTest {

	private static final long MISSING_USER_ID = 999L;

	@Autowired
	private UserQueryService userQueryService;

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

	@Test
	@DisplayName("단건 조회는 닉네임·이미지·상태·가입 시각을 담고 요약 DTO로 바꿀 수 있다")
	void findSnapshot() {
		long userId = insertUser("재현");
		jdbcTemplate.update("UPDATE users SET profile_image_url = 'https://img/1', status = 'SUSPENDED' WHERE user_id = ?",
				userId);

		UserSnapshot snapshot = userQueryService.findSnapshot(userId).orElseThrow();

		assertThat(snapshot.userId()).isEqualTo(userId);
		assertThat(snapshot.nickname()).isEqualTo("재현");
		assertThat(snapshot.profileImageUrl()).isEqualTo("https://img/1");
		assertThat(snapshot.status()).isEqualTo(UserStatus.SUSPENDED);
		assertThat(snapshot.createdAt()).isNotNull();
		assertThat(snapshot.withdrawn()).isFalse();
		assertThat(snapshot.toSummary()).isEqualTo(new UserSummaryResponse(userId, "재현", "https://img/1"));
	}

	@Test
	@DisplayName("없는 id와 null은 empty다")
	void findSnapshotMissing() {
		assertThat(userQueryService.findSnapshot(MISSING_USER_ID)).isEmpty();
		assertThat(userQueryService.findSnapshot(null)).isEmpty();
	}

	@Test
	@DisplayName("일괄 조회는 있는 회원만 담고 없는 id·null·중복은 무시한다")
	void findSnapshotsSkipsMissing() {
		long first = insertUser("첫째");
		long second = insertUser("둘째");

		Map<Long, UserSnapshot> snapshots = userQueryService.findSnapshots(
				Arrays.asList(first, second, MISSING_USER_ID, null, first));

		assertThat(snapshots).containsOnlyKeys(first, second);
		assertThat(snapshots.get(first).nickname()).isEqualTo("첫째");
		assertThat(snapshots.get(second).nickname()).isEqualTo("둘째");
	}

	@Test
	@DisplayName("빈 입력과 null 입력은 빈 맵이다")
	void findSnapshotsEmptyInput() {
		assertThat(userQueryService.findSnapshots(List.of())).isEmpty();
		assertThat(userQueryService.findSnapshots(null)).isEmpty();
		assertThat(userQueryService.findSnapshots(Arrays.asList((Long) null))).isEmpty();
	}

	@Test
	@DisplayName("탈퇴 회원도 조회되며 withdrawn()이 true이고 닉네임은 DB의 탈퇴회원#id 그대로다")
	void withdrawnUserIsIncluded() {
		long active = insertUser("재현");
		long withdrawn = insertUser("떠난회원");
		jdbcTemplate.update("UPDATE users SET nickname = '탈퇴회원#' || user_id, status = 'WITHDRAWN', "
				+ "withdrawn_at = now() WHERE user_id = ?", withdrawn);
		long timestampOnly = insertUser("시각만");
		jdbcTemplate.update("UPDATE users SET withdrawn_at = now() WHERE user_id = ?", timestampOnly);

		Map<Long, UserSnapshot> snapshots = userQueryService.findSnapshots(List.of(active, withdrawn, timestampOnly));

		assertThat(snapshots.get(active).withdrawn()).isFalse();
		assertThat(snapshots.get(withdrawn).withdrawn()).isTrue();
		assertThat(snapshots.get(withdrawn).nickname()).isEqualTo("탈퇴회원#" + withdrawn);
		// 탈퇴 시각만 기록된 비정상 행도 탈퇴로 보인다(User.isWithdrawn과 같은 기준)
		assertThat(snapshots.get(timestampOnly).status()).isEqualTo(UserStatus.WITHDRAWN);
		assertThat(snapshots.get(timestampOnly).withdrawn()).isTrue();
	}

	private long insertUser(String nickname) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
	}

}
