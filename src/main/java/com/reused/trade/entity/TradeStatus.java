package com.reused.trade.entity;

public enum TradeStatus {
	REQUESTED,
	ACCEPTED,
	COMPLETED,
	REJECTED,
	CANCELED;

	public boolean isFinal() {
		return this == COMPLETED || this == REJECTED || this == CANCELED;
	}
}
