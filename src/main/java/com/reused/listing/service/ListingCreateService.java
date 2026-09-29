package com.reused.listing.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.category.entity.Category;
import com.reused.category.repository.CategoryRepository;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.MarketLocks;
import com.reused.image.service.ListingImageService;
import com.reused.listing.dto.request.ListingCreateRequest;
import com.reused.listing.dto.response.ListingCreateResponse;
import com.reused.listing.entity.ItemCondition;
import com.reused.listing.entity.Listing;
import com.reused.listing.entity.TradeMethod;
import com.reused.listing.repository.ListingRepository;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.repository.UserRepository;

@Service
public class ListingCreateService {

	private final ListingRepository listingRepository;
	private final UserRepository userRepository;
	private final CategoryRepository categoryRepository;
	private final ListingImageService listingImageService;
	private final MarketLocks locks;

	public ListingCreateService(ListingRepository listingRepository, UserRepository userRepository,
			CategoryRepository categoryRepository, ListingImageService listingImageService, MarketLocks locks) {
		this.listingRepository = listingRepository;
		this.userRepository = userRepository;
		this.categoryRepository = categoryRepository;
		this.listingImageService = listingImageService;
		this.locks = locks;
	}

	@Transactional
	public ListingCreateResponse create(AuthPrincipal principal, ListingCreateRequest request) {
		locks.users(principal.userId());
		if (principal.role() != UserRole.USER) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		User seller = userRepository.findById(principal.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (seller.getRole() != UserRole.USER || seller.isWithdrawn()) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (seller.isSuspended()) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}

		Category category = categoryRepository.findById(request.categoryId())
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "사용할 수 없는 카테고리입니다."));
		if (!category.isActive()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사용할 수 없는 카테고리입니다.");
		}

		Listing listing = Listing.create(seller.getId(), category.getId(), request.title(),
				request.description(), request.price(), ItemCondition.valueOf(request.itemCondition()),
				TradeMethod.valueOf(request.tradeMethod()));
		Long listingId = listingRepository.saveAndFlush(listing).getId();
		if (request.imageIds() != null && !request.imageIds().isEmpty()) {
			listingImageService.attachToNewListing(listingId, seller.getId(), request.imageIds());
		}
		return new ListingCreateResponse(listingId);
	}

}
