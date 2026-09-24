package com.reused.listing.query;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

@Service
@Transactional(readOnly = true)
public class ListingQueryService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;
	private static final int MAX_PRICE = 100_000_000;
	private static final Set<String> PUBLIC_STATUSES = Set.of("ON_SALE", "RESERVED", "COMPLETED");
	private static final Set<String> SORTS = Set.of("latest", "priceAsc", "priceDesc");

	private final ListingQueryRepository repository;

	public ListingQueryService(ListingQueryRepository repository) {
		this.repository = repository;
	}

	public CursorPageResponse<ListingSummaryResponse> getListings(ListingSearchRequest request,
			@Nullable Long viewerId) {
		ListingSearchRequest search = validateAndNormalize(request);
		int size = search.size();
		ListingCursor cursor = search.cursor() == null
				? null : ListingCursor.decode(search.cursor(), search.sort());

		List<ListingSummaryResponse> rows = repository.findSummaries(search, cursor, viewerId, size + 1);
		boolean hasNext = rows.size() > size;
		List<ListingSummaryResponse> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
		String nextCursor = hasNext ? ListingCursor.encode(search.sort(), items.getLast()) : null;
		return new CursorPageResponse<>(items, nextCursor, hasNext);
	}

	@Transactional
	public ListingDetailResponse getListing(Long id, @Nullable Long viewerId) {
		if (id == null || id <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		if (repository.incrementViewCount(id) == 0) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		return repository.findDetail(id, viewerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	/** Returns the same detail projection after a write without counting it as a view. */
	public ListingDetailResponse getListingAfterUpdate(Long id, Long ownerId) {
		return repository.findDetail(id, ownerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	private static ListingSearchRequest validateAndNormalize(ListingSearchRequest request) {
		if (request == null) {
			request = new ListingSearchRequest(null, null, null, null, null, null, null, null);
		}

		String keyword = request.keyword();
		if (keyword != null && keyword.length() > 50) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		if (keyword != null) {
			keyword = keyword.strip();
			if (keyword.isEmpty()) {
				keyword = null;
			}
		}
		if (request.categoryId() != null && request.categoryId() <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		if (request.status() != null && !PUBLIC_STATUSES.contains(request.status())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		validatePrice(request.minPrice());
		validatePrice(request.maxPrice());
		if (request.minPrice() != null && request.maxPrice() != null
				&& request.minPrice() > request.maxPrice()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}

		String sort = request.sort() == null ? "latest" : request.sort();
		if (!SORTS.contains(sort)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		int size = request.size() == null ? DEFAULT_SIZE : request.size();
		if (size < 1 || size > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		return new ListingSearchRequest(keyword, request.categoryId(), request.status(),
				request.minPrice(), request.maxPrice(), sort, request.cursor(), size);
	}

	private static void validatePrice(Integer price) {
		if (price != null && (price < 0 || price > MAX_PRICE)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
	}
}
