package com.reused.auth.client;

import com.reused.user.entity.AuthProvider;

/**
 * 소셜 제공자의 인가 코드를 제공자 회원번호로 교환한다.
 *
 * <p>제공자를 추가할 때 소셜 로그인 엔드포인트의 계약은 바뀌지 않고 이 인터페이스의 구현체만 늘어난다(ADR-016).
 * 테스트에서는 외부 호출을 대역으로 바꾼다. 제공자 API를 CI에서 실제로 호출하지 않는다.
 */
public interface OAuthProviderClient {

	/**
	 * @return 이 구현체가 담당하는 제공자
	 */
	AuthProvider provider();

	/**
	 * @return 제공자 회원번호. 계정 식별자로 사용한다(ADR-004).
	 */
	String fetchProviderUserId(String code, String redirectUri);

}
