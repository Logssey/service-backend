package com.reused.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.dto.response.MyProfileResponse;
import com.reused.user.entity.User;
import com.reused.user.entity.UserIdentity;
import com.reused.user.repository.UserIdentityRepository;
import com.reused.user.repository.UserRepository;

@Service
public class UserService {

	private static final int NICKNAME_MIN = 2;
	private static final int NICKNAME_MAX = 20;

	private final UserRepository userRepository;
	private final UserIdentityRepository identityRepository;

	public UserService(UserRepository userRepository, UserIdentityRepository identityRepository) {
		this.userRepository = userRepository;
		this.identityRepository = identityRepository;
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

	/**
	 * Access Token은 탈퇴 뒤에도 만료 전까지 서명이 유효하다. 탈퇴했거나 인증 수단이 없는 계정은
	 * 인증되지 않은 것으로 본다. 토큰 재발급의 탈퇴 처리와 같은 규칙이다.
	 */
	@Transactional(readOnly = true)
	public MyProfileResponse getMyProfile(Long userId) {
		User user = userRepository.findById(userId)
				.filter(found -> !found.isWithdrawn())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		UserIdentity identity = identityRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		return MyProfileResponse.of(user, identity);
	}

}
