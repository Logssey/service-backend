package com.reused.trade.query;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.listing.query.CursorPageResponse;
import com.reused.trade.service.TradeUserGuard;
import com.reused.user.entity.User;

@Service
@Transactional(readOnly = true)
public class TradeQueryService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;
	private static final Set<String> ROLES = Set.of("buyer", "seller");
	private static final Set<String> STATUSES =
			Set.of("REQUESTED", "ACCEPTED", "COMPLETED", "REJECTED", "CANCELED");

	private final TradeUserGuard userGuard;
	private final TradeQueryRepository repository;

	public TradeQueryService(TradeUserGuard userGuard, TradeQueryRepository repository) {
		this.userGuard = userGuard;
		this.repository = repository;
	}

	public CursorPageResponse<TradeSummaryResponse> getTrades(AuthPrincipal principal,
			TradeSearchRequest request) {
		User user = userGuard.requireUser(principal, false);
		TradeSearchRequest search = validateAndNormalize(request);
		TradeCursor cursor = search.cursor() == null ? null : TradeCursor.decode(search.cursor());
		List<TradeSummaryResponse> rows = repository.findSummaries(user.getId(), search, cursor,
				search.size() + 1);
		boolean hasNext = rows.size() > search.size();
		List<TradeSummaryResponse> items = List.copyOf(rows.subList(0, Math.min(search.size(), rows.size())));
		String nextCursor = hasNext ? TradeCursor.encode(items.getLast()) : null;
		return new CursorPageResponse<>(items, nextCursor, hasNext);
	}

	public TradeDetailResponse getTrade(AuthPrincipal principal, Long tradeId) {
		User user = userGuard.requireUser(principal, false);
		if (tradeId == null || tradeId <= 0) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		TradeQueryRepository.TradeDetailBase base = repository.findDetail(tradeId, user.getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!base.seller().userId().equals(user.getId()) && !base.buyer().userId().equals(user.getId())) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return new TradeDetailResponse(base.tradeId(), base.status(), base.listing(), base.seller(),
				base.buyer(), base.myRole(), base.chatRoomId(), base.reviewWritten(),
				repository.findHistories(tradeId));
	}

	private static TradeSearchRequest validateAndNormalize(TradeSearchRequest request) {
		if (request == null) {
			request = new TradeSearchRequest(null, null, null, null);
		}
		String role = normalize(request.role());
		if (role != null) {
			role = role.toLowerCase(java.util.Locale.ROOT);
			if (!ROLES.contains(role)) {
				throw new BusinessException(ErrorCode.INVALID_INPUT);
			}
		}
		String status = normalize(request.status());
		if (status != null && !STATUSES.contains(status)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		int size = request.size() == null ? DEFAULT_SIZE : request.size();
		if (size < 1 || size > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		return new TradeSearchRequest(role, status, normalize(request.cursor()), size);
	}

	private static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.strip();
		return normalized.isEmpty() ? null : normalized;
	}
}
