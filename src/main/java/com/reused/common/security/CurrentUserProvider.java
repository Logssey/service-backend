package com.reused.common.security;

import java.util.Optional;

/**
 * 현재 로그인 사용자 조회. 인증 도메인 밖에서 인증 주체가 필요할 때 쓰는 유일한 통로다.
 *
 * <p>다른 도메인이 SecurityContext나 인증 내부 구현에 직접 의존하지 않도록 이 인터페이스로만 연결한다.
 */
public interface CurrentUserProvider {

	/**
	 * @return 비로그인 요청이면 빈 결과. 공개 엔드포인트에서 로그인 여부에 따라 응답을 보강할 때 쓴다.
	 */
	Optional<AuthPrincipal> current();

	/**
	 * @throws com.reused.common.error.BusinessException 비로그인 요청인 경우 UNAUTHENTICATED
	 */
	AuthPrincipal require();

}
