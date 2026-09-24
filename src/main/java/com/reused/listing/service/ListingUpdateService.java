package com.reused.listing.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.category.entity.Category;
import com.reused.category.repository.CategoryRepository;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.listing.dto.request.ListingUpdateRequest;
import com.reused.listing.entity.ItemCondition;
import com.reused.listing.entity.Listing;
import com.reused.listing.entity.TradeMethod;
import com.reused.listing.query.ListingDetailResponse;
import com.reused.listing.query.ListingQueryService;
import com.reused.listing.repository.ListingRepository;

@Service
public class ListingUpdateService {

	private final ListingOwnerGuard ownerGuard;
	private final CategoryRepository categoryRepository;
	private final ListingRepository listingRepository;
	private final ListingQueryService listingQueryService;

	public ListingUpdateService(ListingOwnerGuard ownerGuard, CategoryRepository categoryRepository,
			ListingRepository listingRepository, ListingQueryService listingQueryService) {
		this.ownerGuard = ownerGuard;
		this.categoryRepository = categoryRepository;
		this.listingRepository = listingRepository;
		this.listingQueryService = listingQueryService;
	}

	@Transactional
	public ListingDetailResponse update(AuthPrincipal principal, Long listingId, ListingUpdateRequest request) {
		Listing listing = ownerGuard.requireActiveOwner(principal, listingId);
		if (request.imageIds() != null && !request.imageIds().isEmpty()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이미지 연결은 아직 제공하지 않습니다.");
		}
		if (request.categoryId() != null) {
			Category category = categoryRepository.findById(request.categoryId())
					.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "사용할 수 없는 카테고리입니다."));
			if (!category.isActive()) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "사용할 수 없는 카테고리입니다.");
			}
		}

		listing.update(request.title(), request.description(), request.price(),
				request.itemCondition() == null ? null : ItemCondition.valueOf(request.itemCondition()),
				request.tradeMethod() == null ? null : TradeMethod.valueOf(request.tradeMethod()),
				request.categoryId());
		listingRepository.flush();
		return listingQueryService.getListingAfterUpdate(listingId, principal.userId());
	}
}
