package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.User;
import com.reused.user.entity.UserIdentity;

/**
 * 인증 수단 생성 규칙(ADR-016). 저장 제약은 {@link UserIdentitySchemaIntegrationTest}가 확인한다.
 */
class UserIdentityTest {

	private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

	@Test
	@DisplayName("소셜 인증 수단은 선택 이메일과 동의 시각을 담고 비밀번호는 없다")
	void socialIdentityKeepsEmailAndConsent() {
		UserIdentity identity = UserIdentity.social(User.signUp("재현", NOW), AuthProvider.KAKAO, "1234", "a@b.com", NOW);

		assertThat(identity.getEmail()).isEqualTo("a@b.com");
		assertThat(identity.getEmailConsentAt()).isEqualTo(NOW);
		assertThat(identity.getPasswordHash()).isNull();
		assertThat(identity.hasEmail()).isTrue();
		assertThat(identity.isEmailVerified()).isFalse();
	}

	@Test
	@DisplayName("이메일이 없으면 동의 시각을 넘겨도 저장하지 않는다")
	void socialIdentityWithoutEmailIgnoresConsent() {
		UserIdentity identity = UserIdentity.social(User.signUp("재현", NOW), AuthProvider.KAKAO, "1234", null, NOW);

		assertThat(identity.getEmail()).isNull();
		assertThat(identity.getEmailConsentAt()).isNull();
		assertThat(identity.hasEmail()).isFalse();
	}

	@Test
	@DisplayName("소셜 이메일을 동의 시각 없이 만들 수 없고, LOCAL은 social()로 만들 수 없다")
	void invalidSocialIdentityIsRejected() {
		User user = User.signUp("재현", NOW);

		assertThatThrownBy(() -> UserIdentity.social(user, AuthProvider.KAKAO, "1234", "a@b.com", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> UserIdentity.social(user, AuthProvider.LOCAL, "1234", null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("LOCAL 인증 수단은 항상 이메일이 있고 선택 동의 시각은 쓰지 않는다")
	void localIdentityHasEmailWithoutConsent() {
		UserIdentity identity = UserIdentity.local(User.signUp("재현", NOW), "a@b.com", "hash");

		assertThat(identity.hasEmail()).isTrue();
		assertThat(identity.getEmailConsentAt()).isNull();
	}

}
