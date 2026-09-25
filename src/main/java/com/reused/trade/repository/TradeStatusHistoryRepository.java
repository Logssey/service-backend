package com.reused.trade.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reused.trade.entity.TradeStatusHistory;

public interface TradeStatusHistoryRepository extends JpaRepository<TradeStatusHistory, Long> {
}
