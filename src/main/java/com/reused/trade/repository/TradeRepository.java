package com.reused.trade.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.trade.entity.Trade;
import com.reused.trade.entity.TradeStatus;

public interface TradeRepository extends JpaRepository<Trade, Long> {

	boolean existsByListingIdAndBuyerIdAndStatusIn(Long listingId, Long buyerId,
			Collection<TradeStatus> statuses);
}
