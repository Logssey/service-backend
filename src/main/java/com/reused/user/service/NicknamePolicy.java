package com.reused.user.service;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.entity.User;

/**
 * 닉네임 규칙. 중복 확인, 소셜 온보딩, 이메일 가입, 내 정보 수정이 모두 이 규칙을 쓴다.
 *
 * <p>예약어는 문서에 없는 규칙이다. users.nickname이 UNIQUE라서 누군가 먼저 {@code 탈퇴회원#5}를 가져가면
 * 5번 회원의 탈퇴가 UNIQUE 위반으로 실패한다. {@code 탈퇴한 사용자}는 커뮤니티가 탈퇴 작성자에게 쓰는 표시명이다.
 * 앞뒤 공백만 다른 값도 같은 이름으로 보이므로 공백을 떼고 비교한다.
 */
public final class NicknamePolicy {

	public static final int MIN_LENGTH = 2;
	public static final int MAX_LENGTH = 20;

	static final String WITHDRAWN_DISPLAY_NAME = "탈퇴한 사용자";

	private NicknamePolicy() {
	}

	/**
	 * @throws BusinessException INVALID_INPUT 길이 위반 또는 예약어
	 */
	public static void validate(String nickname) {
		if (nickname == null || nickname.isBlank()
				|| nickname.length() < MIN_LENGTH || nickname.length() > MAX_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "닉네임은 2~20자여야 합니다.");
		}
		String stripped = nickname.strip();
		if (stripped.startsWith(User.WITHDRAWN_NICKNAME_PREFIX) || stripped.equals(WITHDRAWN_DISPLAY_NAME)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사용할 수 없는 닉네임입니다.");
		}
	}

}
