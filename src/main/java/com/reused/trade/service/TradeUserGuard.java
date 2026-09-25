package com.reused.trade.service;

import org.springframework.stereotype.Component;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.repository.UserRepository;

@Component
public class TradeUserGuard {

	private final UserRepository userRepository;

	public TradeUserGuard(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	public User requireUser(AuthPrincipal principal, boolean suspensionBlocked) {
		if (principal.role() != UserRole.USER) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		User user = userRepository.findById(principal.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (user.getRole() != UserRole.USER) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (user.isWithdrawn()) {
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
		if (suspensionBlocked && user.isSuspended()) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}
		return user;
	}
}
