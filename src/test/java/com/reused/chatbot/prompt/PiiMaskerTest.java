package com.reused.chatbot.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * LLM에 보내기 전 입력 속 개인정보 가리기(NFR-EXT-004).
 */
class PiiMaskerTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"메일은 kim.minji+shop@example.co.kr 입니다|메일은 [이메일] 입니다",
			"010-1234-5678로 연락 주세요|[전화번호]로 연락 주세요",
			"01012345678|[전화번호]",
			"011 123 4567 번호|[전화번호] 번호",
			"계좌 110-123-456789|계좌 [번호]",
			"카드 1234567890123456|카드 [번호]",
			"주민번호 900101-1234567|주민번호 [번호]"
	})
	@DisplayName("이메일, 휴대폰 번호, 10자리 이상 숫자열을 가린다")
	void masksPersonalData(String input, String expected) {
		assertThat(PiiMasker.mask(input)).isEqualTo(expected);
	}

	@Test
	@DisplayName("가격·날짜 같은 짧은 숫자와 일반 문장은 그대로 둔다")
	void keepsOrdinaryText() {
		String text = "30000원짜리 자전거를 2026-03-15에 거래했는데 후기는 언제 쓰나요?";

		assertThat(PiiMasker.mask(text)).isEqualTo(text);
	}

}
