package com.reused.common.security;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /api/v1/admin/**}의 관리자 DB 재확인. 역할 검사 자체는 SecurityConfig가 토큰의 role로 먼저 한다.
 *
 * <p>Access Token의 role은 발급 시점 값이고 최대 30분 유효하다(ADR-005). 그래서 역할이 회수되었거나
 * 정지·탈퇴된 관리자가 그 사이 관리자 API를 계속 호출할 수 있다(ADR-006 "서버 측 역할 검증 누락 시 우회").
 * 관리자 요청마다 users 행을 한 번 읽어 이 창을 막는다. 관리자 트래픽은 적어 조회 부담이 없다.
 *
 * <p>A의 관리자 API도 같은 경로 아래 있으므로 자동으로 적용된다. 서비스에서 따로 재확인하지 않는다.
 */
@Component
public class AdminAccessInterceptor implements HandlerInterceptor {

	private final CurrentUserProvider currentUserProvider;
	private final UserRepository userRepository;

	public AdminAccessInterceptor(CurrentUserProvider currentUserProvider, UserRepository userRepository) {
		this.currentUserProvider = currentUserProvider;
		this.userRepository = userRepository;
	}

	/**
	 * 없거나 탈퇴한 사용자는 세션이 끝난 것이므로 401이다(contracts §1.5, §3.3의 탈퇴 토큰 규칙과 같음).
	 * 계정은 살아 있지만 관리자 권한이 없는 경우만 403이다.
	 *
	 * @throws BusinessException UNAUTHENTICATED 사용자가 없거나 탈퇴한 경우
	 * @throws BusinessException FORBIDDEN ADMIN이 아니거나 정지 중인 경우
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		AuthPrincipal principal = currentUserProvider.require();
		User user = userRepository.findById(principal.userId())
				.filter(found -> !found.isWithdrawn())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (!isActiveAdmin(user, Instant.now())) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return true;
	}

	/**
	 * 정지 판정은 {@link User#isSuspendedAt(Instant)}다. 기간이 지난 정지는 해제로 본다.
	 */
	private static boolean isActiveAdmin(User user, Instant now) {
		return user.getRole() == UserRole.ADMIN && !user.isSuspendedAt(now);
	}

}
