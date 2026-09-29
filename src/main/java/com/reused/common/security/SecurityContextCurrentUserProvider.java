package com.reused.common.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

	@Override
	public Optional<AuthPrincipal> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
			return Optional.empty();
		}
		return Optional.of(principal);
	}

	@Override
	public AuthPrincipal require() {
		return current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
	}

}
