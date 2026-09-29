package com.reused.listing.service;

import org.springframework.stereotype.Component;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.MarketLocks;
import com.reused.listing.entity.Listing;
import com.reused.listing.repository.ListingRepository;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.repository.UserRepository;

@Component
class ListingOwnerGuard {

	private final UserRepository userRepository;
	private final ListingRepository listingRepository;
	private final MarketLocks locks;

	ListingOwnerGuard(UserRepository userRepository, ListingRepository listingRepository, MarketLocks locks) {
		this.userRepository = userRepository;
		this.listingRepository = listingRepository;
		this.locks = locks;
	}

	Listing requireActiveOwner(AuthPrincipal principal, Long listingId) {
		locks.users(principal.userId());
		if (principal.role() != UserRole.USER) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		User user = userRepository.findById(principal.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (user.getRole() != UserRole.USER || user.isWithdrawn()) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (user.isSuspended()) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}

		Listing listing = listingRepository.findActiveByIdForUpdate(listingId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!listing.getSellerId().equals(user.getId())) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return listing;
	}
}
