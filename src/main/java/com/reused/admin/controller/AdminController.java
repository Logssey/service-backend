package com.reused.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.reused.admin.dto.AdminDeleteRequest;
import com.reused.admin.dto.AdminListingResponse;
import com.reused.admin.dto.AdminListingSearchRequest;
import com.reused.admin.dto.AdminListingStatusRequest;
import com.reused.admin.dto.AdminListingStatusResponse;
import com.reused.admin.dto.AdminTradeResponse;
import com.reused.admin.dto.AdminTradeSearchRequest;
import com.reused.admin.service.AdminService;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.listing.query.CursorPageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

	private final AdminService adminService;

	public AdminController(AdminService adminService) {
		this.adminService = adminService;
	}

	@GetMapping("/listings")
	public CursorPageResponse<AdminListingResponse> getListings(
			@AuthUser AuthPrincipal principal,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Long sellerId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return adminService.getListings(principal,
				new AdminListingSearchRequest(status, keyword, sellerId, cursor, size));
	}

	@PatchMapping("/listings/{listingId}/status")
	public AdminListingStatusResponse changeListingStatus(@AuthUser AuthPrincipal principal,
			@PathVariable Long listingId, @Valid @RequestBody AdminListingStatusRequest request) {
		return adminService.changeListingStatus(principal, listingId, request);
	}

	@DeleteMapping("/listings/{listingId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteListing(@AuthUser AuthPrincipal principal, @PathVariable Long listingId,
			@Valid @RequestBody AdminDeleteRequest request) {
		adminService.deleteListing(principal, listingId, request);
	}

	@GetMapping("/trades")
	public CursorPageResponse<AdminTradeResponse> getTrades(
			@AuthUser AuthPrincipal principal,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Long userId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return adminService.getTrades(principal, new AdminTradeSearchRequest(status, userId, cursor, size));
	}
}
