package com.reused.wish;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.listing.query.CursorPageResponse;
import com.reused.listing.query.ListingSummaryResponse;

@RestController
public class WishController {

	private final WishService service;

	public WishController(WishService service) {
		this.service = service;
	}

	@PostMapping("/api/v1/listings/{listingId}/wish")
	public WishResponse add(@AuthUser AuthPrincipal principal, @PathVariable Long listingId) {
		return service.add(principal, listingId);
	}

	@DeleteMapping("/api/v1/listings/{listingId}/wish")
	public WishResponse remove(@AuthUser AuthPrincipal principal, @PathVariable Long listingId) {
		return service.remove(principal, listingId);
	}

	@GetMapping("/api/v1/wishes")
	public CursorPageResponse<ListingSummaryResponse> list(@AuthUser AuthPrincipal principal,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return service.list(principal, cursor, size);
	}
}
