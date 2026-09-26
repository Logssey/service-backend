package com.reused.admin.api;

/**
 * 관리자 대시보드의 A 소유 수치. 정의는 A 구현이 지킨다.
 *
 * @param totalListings 삭제되지 않은(deleted_at IS NULL) 게시글. HIDDEN 포함
 * @param onSaleListings status = 'ON_SALE' AND deleted_at IS NULL
 * @param completedTrades trades.status = 'COMPLETED'
 */
public record MarketplaceMetrics(long totalListings, long onSaleListings, long completedTrades) {

	/** A 구현이 없을 때. A 코드가 없으면 실제로 데이터가 없다 */
	public static MarketplaceMetrics empty() {
		return new MarketplaceMetrics(0, 0, 0);
	}

}
