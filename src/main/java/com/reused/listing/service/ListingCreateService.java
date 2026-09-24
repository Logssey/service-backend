package com.reused.listing.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.category.entity.Category;
import com.reused.category.repository.CategoryRepository;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
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

	public ListingCreateService(ListingRepository listingRepository, UserRepository userRepository,
			CategoryRepository categoryRepository) {
		this.listingRepository = listingRepository;
		this.userRepository = userRepository;
		this.categoryRepository = categoryRepository;
	}

	@Transactional
	public ListingCreateResponse create(AuthPrincipal principal, ListingCreateRequest request) {
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

		// 이미지 메타데이터 검증 및 연결은 IMAGES 업로드 흐름과 함께 추가한다.
		if (request.imageIds() != null && !request.imageIds().isEmpty()) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이미지 연결은 아직 제공되지 않습니다.");
		}

		Listing listing = Listing.create(seller.getId(), category.getId(), request.title(),
				request.description(), request.price(), ItemCondition.valueOf(request.itemCondition()),
				TradeMethod.valueOf(request.tradeMethod()));
		return new ListingCreateResponse(listingRepository.save(listing).getId());
	}

}
