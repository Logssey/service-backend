package com.reused.chatbot.prompt;

import java.util.regex.Pattern;

/**
 * 사용자가 직접 적은 문장 속 개인정보를 LLM에 보내기 전에 가린다(NFR-EXT-004).
 *
 * <p>이메일 → {@code [이메일]}, 휴대폰 번호 → {@code [전화번호]}, 10자리 이상 숫자열(하이픈 허용, 계좌·카드 등) →
 * {@code [번호]}. 이메일에 숫자가 섞일 수 있어 이메일을 먼저, 휴대폰을 긴 숫자열보다 먼저 바꾼다.
 */
public final class PiiMasker {

	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
	private static final Pattern MOBILE_PHONE = Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)");
	private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d(-?\\d){9,}(?!\\d)");

	private static final String EMAIL_MASK = "[이메일]";
	private static final String PHONE_MASK = "[전화번호]";
	private static final String NUMBER_MASK = "[번호]";

	private PiiMasker() {
	}

	public static String mask(String text) {
		String masked = EMAIL.matcher(text).replaceAll(EMAIL_MASK);
		masked = MOBILE_PHONE.matcher(masked).replaceAll(PHONE_MASK);
		return LONG_NUMBER.matcher(masked).replaceAll(NUMBER_MASK);
	}

}
