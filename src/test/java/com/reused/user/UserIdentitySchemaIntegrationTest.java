package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;

/**
 * 004 적용 뒤의 user_identities 제약(ADR-019). 애플리케이션을 거치지 않고 SQL로 넣어 DB가 규칙을 스스로 지키는지 본다.
 * 애플리케이션 경로는 SocialEmailIntegrationTest가 다룬다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserIdentitySchemaIntegrationTest {

	private static final String EMAIL = "same@example.com";
	private static final String HASH = "$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private int nicknameSequence;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		nicknameSequence = 0;
	}

	// --- 소셜 인증 수단의 컬럼 규칙 ---

	@Test
	@DisplayName("소셜 인증 수단은 동의 시각과 함께 이메일을 가질 수 있고 소유 확인 시각도 기록할 수 있다")
	void socialIdentityMayHoldEmailWithConsent() {
		insertIdentity(newUser(), "KAKAO", EMAIL, null, true, true);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM user_identities WHERE provider = 'KAKAO' AND email = ?", Long.class, EMAIL))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("소셜 인증 수단에 비밀번호 해시를 두면 ck_user_identities_social_credential 위반이다(이메일이 없어도)")
	void socialIdentityCannotHoldPassword() {
		long userId = newUser();

		assertViolation(() -> insertIdentity(userId, "KAKAO", null, HASH, false, false),
				"ck_user_identities_social_credential");
		assertViolation(() -> insertIdentity(userId, "KAKAO", EMAIL, HASH, false, true),
				"ck_user_identities_social_credential");
	}

	@Test
	@DisplayName("소셜 이메일을 동의 시각 없이 두면 ck_user_identities_social_email_consent 위반이다")
	void socialEmailRequiresConsent() {
		assertViolation(() -> insertIdentity(newUser(), "KAKAO", EMAIL, null, false, false),
				"ck_user_identities_social_email_consent");
	}

	@Test
	@DisplayName("이메일 없이 소유 확인 시각만 두면 ck_user_identities_verified_email 위반이다")
	void verifiedAtRequiresEmail() {
		assertViolation(() -> insertIdentity(newUser(), "KAKAO", null, null, true, false),
				"ck_user_identities_verified_email");
	}

	// --- LOCAL 규칙은 그대로 ---

	@Test
	@DisplayName("LOCAL은 여전히 이메일이 필수이고, 가입 필수 항목이라 선택 동의 시각 없이 저장된다")
	void localRulesAreUnchanged() {
		assertViolation(() -> insertIdentity(newUser(), "LOCAL", null, HASH, false, false),
				"ck_user_identities_local_email");

		insertIdentity(newUser(), "LOCAL", EMAIL, HASH, false, false);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_identities WHERE provider = 'LOCAL'", Long.class))
				.isEqualTo(1);
	}

	// --- 유일성 ---

	@Test
	@DisplayName("소유 확인 전 소셜 이메일은 겹쳐도 되고, 확인을 마친 소셜 이메일은 하나뿐이다(uq_user_identities_email_social_verified)")
	void verifiedSocialEmailIsUnique() {
		insertIdentity(newUser(), "KAKAO", EMAIL, null, false, true);
		insertIdentity(newUser(), "KAKAO", EMAIL, null, false, true);
		insertIdentity(newUser(), "KAKAO", EMAIL, null, true, true);

		assertViolation(() -> insertIdentity(newUser(), "KAKAO", EMAIL, null, true, true),
				"uq_user_identities_email_social_verified");
		assertViolation(() -> jdbcTemplate.update(
				"UPDATE user_identities SET email_verified_at = now() WHERE identity_id = 1"),
				"uq_user_identities_email_social_verified");
	}

	@Test
	@DisplayName("LOCAL과 소셜은 확인을 마친 같은 이메일을 함께 가질 수 있고, LOCAL끼리는 uq_user_identities_email_local 위반이다")
	void localAndSocialAreSeparateScopes() {
		insertIdentity(newUser(), "LOCAL", EMAIL, HASH, true, false);
		insertIdentity(newUser(), "KAKAO", EMAIL, null, true, true);

		assertViolation(() -> insertIdentity(newUser(), "LOCAL", EMAIL, HASH, false, false),
				"uq_user_identities_email_local");
	}

	// --- helpers ---

	private long newUser() {
		nicknameSequence++;
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				"회원" + nicknameSequence);
	}

	private void insertIdentity(long userId, String provider, String email, String passwordHash, boolean verified,
			boolean consented) {
		jdbcTemplate.update("""
				INSERT INTO user_identities
				    (user_id, provider, provider_user_id, email, password_hash, email_verified_at, email_consent_at)
				VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, CASE WHEN ? THEN now() END)""",
				userId, provider, provider + "-" + userId, email, passwordHash, verified, consented);
	}

	private static void assertViolation(Runnable statement, String constraintName) {
		assertThatThrownBy(statement::run)
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(constraintName);
	}

}
