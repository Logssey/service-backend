package com.reused.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.admin.dto.AdminDeleteRequest;
import com.reused.admin.dto.AdminListingResponse;
import com.reused.admin.dto.AdminListingSearchRequest;
import com.reused.admin.dto.AdminListingStatusRequest;
import com.reused.admin.dto.AdminListingStatusResponse;
import com.reused.admin.dto.AdminTradeResponse;
import com.reused.admin.dto.AdminTradeSearchRequest;
import com.reused.admin.query.AdminCursor;
import com.reused.admin.repository.AdminRepository;
import com.reused.audit.service.AuditService;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.ActorGuard;
import com.reused.common.security.AuthPrincipal;
import com.reused.image.service.ListingImageService;
import com.reused.listing.query.CursorPageResponse;
import com.reused.trade.query.ListingBriefResponse;

@Service
public class AdminService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;
	private static final String LISTING_CURSOR_SCOPE = "admin-listings";
	private static final String TRADE_CURSOR_SCOPE = "admin-trades";
	private static final Set<String> LISTING_STATUSES =
			Set.of("ON_SALE", "RESERVED", "COMPLETED", "HIDDEN");
	private static final Set<String> TRADE_STATUSES =
			Set.of("REQUESTED", "ACCEPTED", "COMPLETED", "REJECTED", "CANCELED");
	private static final Set<String> RESTORABLE_STATUSES =
			Set.of("ON_SALE", "RESERVED", "COMPLETED");

	private final ActorGuard actorGuard;
	private final AdminRepository repository;
	private final AuditService auditService;
	private final ListingImageService listingImageService;

	public AdminService(ActorGuard actorGuard, AdminRepository repository, AuditService auditService,
			ListingImageService listingImageService) {
		this.actorGuard = actorGuard;
		this.repository = repository;
		this.auditService = auditService;
		this.listingImageService = listingImageService;
	}

	@Transactional(readOnly = true)
	public CursorPageResponse<AdminListingResponse> getListings(AuthPrincipal principal,
			AdminListingSearchRequest request) {
		actorGuard.admin(principal);
		AdminListingSearchRequest search = normalize(request);
		AdminCursor cursor = search.cursor() == null ? null
				: AdminCursor.decode(search.cursor(), LISTING_CURSOR_SCOPE);
		List<AdminListingResponse> rows = repository.findListings(search,
				cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(),
				search.size() + 1);
		boolean hasNext = rows.size() > search.size();
		List<AdminListingResponse> items = List.copyOf(rows.subList(0, Math.min(search.size(), rows.size())));
		String nextCursor = hasNext ? AdminCursor.encode(LISTING_CURSOR_SCOPE,
				items.getLast().createdAt(), items.getLast().listingId()) : null;
		return new CursorPageResponse<>(items, nextCursor, hasNext);
	}

	@Transactional(readOnly = true)
	public CursorPageResponse<AdminTradeResponse> getTrades(AuthPrincipal principal,
			AdminTradeSearchRequest request) {
		actorGuard.admin(principal);
		AdminTradeSearchRequest search = normalize(request);
		AdminCursor cursor = search.cursor() == null ? null
				: AdminCursor.decode(search.cursor(), TRADE_CURSOR_SCOPE);
		List<AdminTradeResponse> rows = repository.findTrades(search,
				cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(),
				search.size() + 1);
		boolean hasNext = rows.size() > search.size();
		List<AdminTradeResponse> page = rows.subList(0, Math.min(search.size(), rows.size()));
		Map<Long, String> thumbnails = listingImageService.thumbnailsForListings(
				page.stream().map(item -> item.listing().listingId()).toList());
		List<AdminTradeResponse> items = page.stream()
				.map(item -> withThumbnail(item, thumbnails.get(item.listing().listingId())))
				.toList();
		String nextCursor = hasNext ? AdminCursor.encode(TRADE_CURSOR_SCOPE,
				items.getLast().requestedAt(), items.getLast().tradeId()) : null;
		return new CursorPageResponse<>(items, nextCursor, hasNext);
	}

	@Transactional
	public AdminListingStatusResponse changeListingStatus(AuthPrincipal principal, Long listingId,
			AdminListingStatusRequest request) {
		Long actorId = actorGuard.admin(principal);
		AdminRepository.ListingState listing = requireListing(listingId);
		String reason = request.reason().strip();
		if ("HIDDEN".equals(request.status())) {
			if ("HIDDEN".equals(listing.status())) {
				throw new BusinessException(ErrorCode.CONFLICT);
			}
			Instant changedAt = Instant.now();
			repository.updateListingStatus(listingId, "HIDDEN", changedAt);
			auditService.record(actorId, "LISTING_HIDE", "LISTING", listingId,
					Map.of("reason", reason, "beforeStatus", listing.status(), "afterStatus", "HIDDEN"));
			return new AdminListingStatusResponse(listingId, "HIDDEN");
		}

		if (!"HIDDEN".equals(listing.status())) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		String hiddenFromStatus = repository.findLatestHiddenPreviousStatus(listingId)
				.filter(RESTORABLE_STATUSES::contains)
				.orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
						"숨김 이전 상태를 확인할 수 없습니다."));
		String restoredStatus = repository.deriveRestoredStatus(listingId, hiddenFromStatus);
		Instant changedAt = Instant.now();
		repository.updateListingStatus(listingId, restoredStatus, changedAt);
		auditService.record(actorId, "LISTING_RESTORE", "LISTING", listingId,
				Map.of("reason", reason, "beforeStatus", "HIDDEN",
						"hiddenFromStatus", hiddenFromStatus, "afterStatus", restoredStatus));
		return new AdminListingStatusResponse(listingId, restoredStatus);
	}

	@Transactional
	public void deleteListing(AuthPrincipal principal, Long listingId, AdminDeleteRequest request) {
		Long actorId = actorGuard.admin(principal);
		AdminRepository.ListingState listing = requireListing(listingId);
		if (repository.hasActiveTrade(listingId)) {
			throw new BusinessException(ErrorCode.CONFLICT,
					"진행 중인 거래가 있는 게시글은 삭제할 수 없습니다.");
		}
		String reason = request.reason().strip();
		Instant changedAt = Instant.now();
		repository.softDeleteListing(listingId, actorId, changedAt);
		auditService.record(actorId, "LISTING_DELETE", "LISTING", listingId,
				Map.of("reason", reason, "beforeStatus", listing.status()));
	}

	private AdminRepository.ListingState requireListing(Long listingId) {
		if (listingId == null || listingId <= 0) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		return repository.findListingForUpdate(listingId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	private static AdminListingSearchRequest normalize(AdminListingSearchRequest request) {
		String status = normalizeText(request == null ? null : request.status());
		if (status != null && !LISTING_STATUSES.contains(status)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		String keyword = normalizeText(request == null ? null : request.keyword());
		if (keyword != null && keyword.length() > 100) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		Long sellerId = request == null ? null : request.sellerId();
		if (sellerId != null && sellerId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		String cursor = normalizeText(request == null ? null : request.cursor());
		int size = pageSize(request == null ? null : request.size());
		return new AdminListingSearchRequest(status, keyword, sellerId, cursor, size);
	}

	private static AdminTradeSearchRequest normalize(AdminTradeSearchRequest request) {
		String status = normalizeText(request == null ? null : request.status());
		if (status != null && !TRADE_STATUSES.contains(status)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		Long userId = request == null ? null : request.userId();
		if (userId != null && userId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		String cursor = normalizeText(request == null ? null : request.cursor());
		int size = pageSize(request == null ? null : request.size());
		return new AdminTradeSearchRequest(status, userId, cursor, size);
	}

	private static int pageSize(Integer size) {
		int value = size == null ? DEFAULT_SIZE : size;
		if (value < 1 || value > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		return value;
	}

	private static String normalizeText(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.strip();
		return normalized.isEmpty() ? null : normalized;
	}

	private static AdminTradeResponse withThumbnail(AdminTradeResponse item, String thumbnail) {
		ListingBriefResponse listing = new ListingBriefResponse(item.listing().listingId(),
				item.listing().title(), item.listing().price(), thumbnail);
		return new AdminTradeResponse(item.tradeId(), item.status(), listing, item.seller(), item.buyer(),
				item.requestedAt(), item.completedAt());
	}
}
