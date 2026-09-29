package com.reused.admin.api;

/**
 * 중고거래 운영 수치(B가 정의, A가 구현). 관리자 대시보드의 게시글·거래 수치에 쓴다.
 * B는 listings·trades 테이블을 직접 읽지 않는다. 구현이 없으면 대시보드는 세 값을 0으로 낸다.
 */
public interface MarketplaceMetricsPort {

	/**
	 * 대시보드 요청마다 한 번 부른다. 캐시하지 않는다(redis-keys.md에 대시보드 키가 없다).
	 */
	MarketplaceMetrics currentMetrics();

}
