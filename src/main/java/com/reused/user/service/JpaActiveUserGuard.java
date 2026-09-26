package com.reused.user.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.api.ActiveUserGuard;
import com.reused.user.entity.User;
import com.reused.user.repository.UserRepository;

/**
 * 탈퇴를 401로 보는 것은 토큰은 유효해도 인증되지 않은 것으로 보기 때문이다({@link UserService#getMyProfile}과 같은 규칙).
 */
@Service
public class JpaActiveUserGuard implements ActiveUserGuard {

	private final UserRepository userRepository;

	public JpaActiveUserGuard(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public void requireActive(Long userId) {
		User user = findMember(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (user.isSuspendedAt(Instant.now())) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActive(Long userId) {
		Instant now = Instant.now();
		return findMember(userId).filter(user -> !user.isSuspendedAt(now)).isPresent();
	}

	@Override
	@Transactional(readOnly = true)
	public void requireMember(Long userId) {
		findMember(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
	}

	/** 없거나 탈퇴했으면 empty */
	private Optional<User> findMember(Long userId) {
		if (userId == null) {
			return Optional.empty();
		}
		return userRepository.findById(userId).filter(user -> !user.isWithdrawn());
	}

}
