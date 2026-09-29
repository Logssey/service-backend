package com.reused.listing.entity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매 게시글. 컬럼 구성은 schema/001_init.sql의 listings 테이블과 맞춘다.
 */
@Entity
@Table(name = "listings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Listing {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "listing_id")
	private Long id;

	@Column(name = "seller_id", nullable = false)
	private Long sellerId;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 2000)
	private String description;

	@Column(nullable = false)
	private int price;

	@Enumerated(EnumType.STRING)
	@Column(name = "item_condition", nullable = false, length = 20)
	private ItemCondition itemCondition;

	@Enumerated(EnumType.STRING)
	@Column(name = "trade_method", nullable = false, length = 20)
	private TradeMethod tradeMethod;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ListingStatus status;

	@Column(name = "wish_count", nullable = false)
	private int wishCount;

	@Column(name = "view_count", nullable = false)
	private int viewCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private Long deletedBy;

	private Listing(Long sellerId, Long categoryId, String title, String description, int price,
			ItemCondition itemCondition, TradeMethod tradeMethod) {
		this.sellerId = sellerId;
		this.categoryId = categoryId;
		this.title = title;
		this.description = description;
		this.price = price;
		this.itemCondition = itemCondition;
		this.tradeMethod = tradeMethod;
		this.status = ListingStatus.ON_SALE;
		this.wishCount = 0;
		this.viewCount = 0;
		this.createdAt = Instant.now();
	}

	public static Listing create(Long sellerId, Long categoryId, String title, String description, int price,
			ItemCondition itemCondition, TradeMethod tradeMethod) {
		return new Listing(sellerId, categoryId, title, description, price, itemCondition, tradeMethod);
	}

	public void update(String title, String description, Integer price, ItemCondition itemCondition,
			TradeMethod tradeMethod, Long categoryId) {
		if (title != null) {
			this.title = title;
		}
		if (description != null) {
			this.description = description;
		}
		if (price != null) {
			this.price = price;
		}
		if (itemCondition != null) {
			this.itemCondition = itemCondition;
		}
		if (tradeMethod != null) {
			this.tradeMethod = tradeMethod;
		}
		if (categoryId != null) {
			this.categoryId = categoryId;
		}
		this.updatedAt = Instant.now();
	}

	public void softDelete(Long deletedBy) {
		Instant now = Instant.now();
		this.deletedAt = now;
		this.deletedBy = deletedBy;
		this.updatedAt = now;
	}

	public void reserve(Instant changedAt) {
		this.status = ListingStatus.RESERVED;
		this.updatedAt = changedAt;
	}

	public void reopen(Instant changedAt) {
		this.status = ListingStatus.ON_SALE;
		this.updatedAt = changedAt;
	}

	public void complete(Instant changedAt) {
		this.status = ListingStatus.COMPLETED;
		this.updatedAt = changedAt;
	}

}
