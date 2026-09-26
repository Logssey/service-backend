package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.service.NicknamePolicy;

class NicknamePolicyTest {

	@ParameterizedTest
	@ValueSource(strings = { "재현", "abcdefghijklmnopqrst", "탈퇴회원", "탈퇴한사용자", "회원#1" })
	@DisplayName("2~20자이고 예약어가 아니면 통과한다")
	void acceptsValidNicknames(String nickname) {
		assertThatCode(() -> NicknamePolicy.validate(nickname)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   ", "a", "abcdefghijklmnopqrstu" })
	@DisplayName("비었거나 공백뿐이거나 2~20자를 벗어나면 400 길이 메시지다")
	void rejectsLength(String nickname) {
		assertThatThrownBy(() -> NicknamePolicy.validate(nickname))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
					assertThat(e.getMessage()).isEqualTo("닉네임은 2~20자여야 합니다.");
				});
	}

	@ParameterizedTest
	@ValueSource(strings = { "탈퇴회원#5", "탈퇴회원#", "탈퇴회원#abc", "탈퇴한 사용자", " 탈퇴한 사용자 ", " 탈퇴회원#1" })
	@DisplayName("탈퇴회원# 접두어와 탈퇴한 사용자는 예약어라 400이다. 앞뒤 공백만 다른 값도 막는다")
	void rejectsReservedNames(String nickname) {
		assertThatThrownBy(() -> NicknamePolicy.validate(nickname))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
					assertThat(e.getMessage()).isEqualTo("사용할 수 없는 닉네임입니다.");
				});
	}

	@Test
	@DisplayName("길이 검사가 예약어 검사보다 먼저다")
	void lengthIsCheckedFirst() {
		assertThatThrownBy(() -> NicknamePolicy.validate("탈퇴회원#" + "1".repeat(20)))
				.hasMessage("닉네임은 2~20자여야 합니다.");
	}

}
