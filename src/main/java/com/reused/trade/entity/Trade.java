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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "trade_id")
	private Long id;

	@Column(name = "listing_id", nullable = false)
	private Long listingId;

	@Column(name = "seller_id", nullable = false)
	private Long sellerId;

	@Column(name = "buyer_id", nullable = false)
	private Long buyerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TradeStatus status;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "closed_by")
	private Long closedBy;

	@Version
	@Column(nullable = false)
	private int version;

	private Trade(Long listingId, Long sellerId, Long buyerId, Instant requestedAt) {
		this.listingId = listingId;
		this.sellerId = sellerId;
		this.buyerId = buyerId;
		this.status = TradeStatus.REQUESTED;
		this.requestedAt = requestedAt;
	}

	public static Trade request(Long listingId, Long sellerId, Long buyerId, Instant requestedAt) {
		return new Trade(listingId, sellerId, buyerId, requestedAt);
	}

	public void accept(Instant changedAt) {
		this.status = TradeStatus.ACCEPTED;
		this.acceptedAt = changedAt;
	}

	public void reject(Long changedBy, Instant changedAt) {
		this.status = TradeStatus.REJECTED;
		close(changedBy, changedAt);
	}

	public void cancel(Long changedBy, Instant changedAt) {
		this.status = TradeStatus.CANCELED;
		close(changedBy, changedAt);
	}

	public void complete(Instant changedAt) {
		this.status = TradeStatus.COMPLETED;
		this.completedAt = changedAt;
	}

	private void close(Long changedBy, Instant changedAt) {
		this.closedBy = changedBy;
		this.closedAt = changedAt;
	}
}
