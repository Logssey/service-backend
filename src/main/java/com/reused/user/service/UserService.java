package com.reused.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.repository.UserRepository;

@Service
public class UserService {

	private static final int NICKNAME_MIN = 2;
	private static final int NICKNAME_MAX = 20;

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * 닉네임은 프로필에 공개되는 값이라 존재 여부를 노출해도 계정 열거 문제가 없다.
	 * 이메일에는 같은 확인 API를 두지 않는다(NFR-AUTH-018).
	 */
	@Transactional(readOnly = true)
	public boolean isNicknameAvailable(String nickname) {
		if (nickname == null || nickname.isBlank()
				|| nickname.length() < NICKNAME_MIN || nickname.length() > NICKNAME_MAX) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "닉네임은 2~20자여야 합니다.");
		}
		return !userRepository.existsByNickname(nickname);
	}

}
