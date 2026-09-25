package com.reused.wish;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.ActorGuard;
import com.reused.common.security.AuthPrincipal;
import com.reused.image.service.ListingImageService;
import com.reused.listing.query.CursorPageResponse;
import com.reused.listing.query.ListingSummaryResponse;
import com.reused.wish.WishRepository.WishItem;

@Service
public class WishService {

	private final ActorGuard actors;
	private final WishRepository repository;
	private final ListingImageService images;

	public WishService(ActorGuard actors, WishRepository repository, ListingImageService images) {
		this.actors = actors;
		this.repository = repository;
		this.images = images;
	}

	@Transactional
	public WishResponse add(AuthPrincipal principal, Long listingId) {
		long userId = actors.user(principal, false);
		validateId(listingId);
		var listing = repository.lockListing(listingId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!listing.visible()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		int added = repository.add(userId, listingId);
		if (added > 0) {
			repository.updateCount(listingId, added);
		}
		return new WishResponse(true, listing.wishCount() + added);
	}

	@Transactional
	public WishResponse remove(AuthPrincipal principal, Long listingId) {
		long userId = actors.user(principal, false);
		validateId(listingId);
		var listing = repository.lockListing(listingId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		// A member can still clean up their own wishes after a listing is hidden or soft-deleted.
		int removed = repository.remove(userId, listingId);
		if (removed > 0) {
			repository.updateCount(listingId, -removed);
		}
		return new WishResponse(false, listing.wishCount() - removed);
	}

	@Transactional(readOnly = true)
	public CursorPageResponse<ListingSummaryResponse> list(AuthPrincipal principal, String encodedCursor,
			Integer requestedSize) {
		long userId = actors.user(principal, false);
		int size = requestedSize == null ? 20 : requestedSize;
		if (size < 1 || size > 100) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		WishCursor cursor = encodedCursor == null ? null : WishCursor.decode(encodedCursor, userId);
		List<WishItem> rows = repository.list(userId, cursor, size + 1);
		boolean hasNext = rows.size() > size;
		List<WishItem> page = rows.subList(0, Math.min(size, rows.size()));
		Map<Long, String> thumbnails = images.thumbnailsForListings(
				page.stream().map(row -> row.listing().listingId()).toList());
		List<ListingSummaryResponse> items = page.stream().map(row -> {
			ListingSummaryResponse item = row.listing();
			return new ListingSummaryResponse(item.listingId(), item.title(), item.price(), item.status(),
					item.itemCondition(), thumbnails.get(item.listingId()), item.wishCount(), item.seller(),
					item.createdAt());
		}).toList();
		String nextCursor = hasNext
				? new WishCursor(page.getLast().wishedAt(), page.getLast().wishId()).encode(userId) : null;
		return new CursorPageResponse<>(items, nextCursor, hasNext);
	}

	private static void validateId(Long listingId) {
		if (listingId == null || listingId <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
	}
}
