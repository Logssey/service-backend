package com.reused.auth.client;

/**
 * 카카오 인가 코드를 회원번호로 교환한다.
 *
 * <p>인터페이스로 둔 이유는 테스트에서 외부 호출을 대역으로 바꾸기 위함이다.
 * 카카오 API를 CI에서 실제로 호출하지 않는다.
 */
public interface KakaoOAuthClient {

	/**
	 * @return 카카오 회원번호. 계정 식별자로 사용한다(ADR-004).
	 */
	String fetchProviderUserId(String code, String redirectUri);

}
