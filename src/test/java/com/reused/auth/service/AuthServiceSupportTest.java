package com.reused.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 인증 서비스의 패키지 내부 도우미. 선택 이메일 정규화(ADR-019)와 제약 이름 판정.
 */
class AuthServiceSupportTest {

	@Test
	@DisplayName("선택 이메일은 null·빈 문자열이면 입력하지 않은 것(null)이고, 값이 있으면 이메일 가입과 같게 정규화한다")
	void optionalEmailNormalization() {
		assertThat(EmailAuthService.normalizeOptionalEmail(null)).isNull();
		assertThat(EmailAuthService.normalizeOptionalEmail("")).isNull();
		assertThat(EmailAuthService.normalizeOptionalEmail("Kakao@Example.COM")).isEqualTo("kakao@example.com");
	}

	@Test
	@DisplayName("Hibernate가 뽑은 제약 이름은 대소문자와 무관하게 판정한다")
	void constraintNameFromHibernate() {
		DataIntegrityViolationException e = new DataIntegrityViolationException("wrapped",
				new ConstraintViolationException("violation", new SQLException("ignored", "23505"),
						"UQ_User_Identities_Provider_Identity"));

		assertThat(ConstraintNames.of(e)).isEqualTo("uq_user_identities_provider_identity");
		assertThat(ConstraintNames.matches(e, "uq_user_identities_provider_identity")).isTrue();
		assertThat(ConstraintNames.matches(e, "uq_user_identities_user_provider")).isFalse();
	}

	@Test
	@DisplayName("서버 메시지가 영어가 아니어서 Hibernate가 이름을 뽑지 못하면 메시지의 따옴표 친 이름으로 판정한다")
	void constraintNameFromLocalizedMessage() {
		DataIntegrityViolationException e = new DataIntegrityViolationException("wrapped", new SQLException(
				"오류: 중복된 키 값이 \"uq_user_identities_email_social_verified\" 고유 제약 조건을 위반함", "23505"));

		assertThat(ConstraintNames.of(e)).isNull();
		assertThat(ConstraintNames.matches(e, "uq_user_identities_email_social_verified")).isTrue();
		assertThat(ConstraintNames.matches(e, "uq_user_identities_email")).isFalse();
	}

}
