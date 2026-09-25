package com.reused.trade.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trade_status_histories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeStatusHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "history_id")
	private Long id;

	@Column(name = "trade_id", nullable = false)
	private Long tradeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "before_status", length = 20)
	private TradeStatus beforeStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "after_status", nullable = false, length = 20)
	private TradeStatus afterStatus;

	@Column(name = "changed_by")
	private Long changedBy;

	@Column(length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private TradeStatusHistory(Long tradeId, TradeStatus beforeStatus, TradeStatus afterStatus,
			Long changedBy, String reason, Instant createdAt) {
		this.tradeId = tradeId;
		this.beforeStatus = beforeStatus;
		this.afterStatus = afterStatus;
		this.changedBy = changedBy;
		this.reason = reason;
		this.createdAt = createdAt;
	}

	public static TradeStatusHistory changed(Long tradeId, TradeStatus beforeStatus,
			TradeStatus afterStatus, Long changedBy, String reason, Instant createdAt) {
		return new TradeStatusHistory(tradeId, beforeStatus, afterStatus, changedBy, reason, createdAt);
	}
}
