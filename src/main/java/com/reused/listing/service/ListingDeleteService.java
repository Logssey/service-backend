package com.reused.listing.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.listing.entity.Listing;

@Service
public class ListingDeleteService {

	private final ListingOwnerGuard ownerGuard;
	private final JdbcTemplate jdbcTemplate;

	public ListingDeleteService(ListingOwnerGuard ownerGuard, JdbcTemplate jdbcTemplate) {
		this.ownerGuard = ownerGuard;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public void delete(AuthPrincipal principal, Long listingId) {
		Listing listing = ownerGuard.requireActiveOwner(principal, listingId);
		Boolean hasActiveTrade = jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM trades
				              WHERE listing_id = ? AND status IN ('REQUESTED', 'ACCEPTED'))
				""", Boolean.class, listingId);
		if (Boolean.TRUE.equals(hasActiveTrade)) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		listing.softDelete(principal.userId());
	}
}
