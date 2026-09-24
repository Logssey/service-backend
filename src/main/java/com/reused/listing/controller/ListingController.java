package com.reused.listing.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.common.security.CurrentUserProvider;
import com.reused.listing.dto.request.ListingCreateRequest;
import com.reused.listing.dto.response.ListingCreateResponse;
import com.reused.listing.query.CursorPageResponse;
import com.reused.listing.query.ListingDetailResponse;
import com.reused.listing.query.ListingQueryService;
import com.reused.listing.query.ListingSearchRequest;
import com.reused.listing.query.ListingSummaryResponse;
import com.reused.listing.service.ListingCreateService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

	private final ListingCreateService listingCreateService;
	private final ListingQueryService listingQueryService;
	private final CurrentUserProvider currentUserProvider;

	public ListingController(ListingCreateService listingCreateService, ListingQueryService listingQueryService,
			CurrentUserProvider currentUserProvider) {
		this.listingCreateService = listingCreateService;
		this.listingQueryService = listingQueryService;
		this.currentUserProvider = currentUserProvider;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ListingCreateResponse create(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody ListingCreateRequest request) {
		return listingCreateService.create(principal, request);
	}

	@GetMapping
	public CursorPageResponse<ListingSummaryResponse> getListings(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Long categoryId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer minPrice,
			@RequestParam(required = false) Integer maxPrice,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		Long viewerId = currentUserProvider.current().map(AuthPrincipal::userId).orElse(null);
		return listingQueryService.getListings(new ListingSearchRequest(keyword, categoryId, status,
				minPrice, maxPrice, sort, cursor, size), viewerId);
	}

	@GetMapping("/{listingId}")
	public ListingDetailResponse getListing(@PathVariable Long listingId) {
		Long viewerId = currentUserProvider.current().map(AuthPrincipal::userId).orElse(null);
		return listingQueryService.getListing(listingId, viewerId);
	}

}
