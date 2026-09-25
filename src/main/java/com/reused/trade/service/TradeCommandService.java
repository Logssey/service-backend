package com.reused.trade.service;

import java.time.Instant;
import java.util.EnumSet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.listing.entity.Listing;
import com.reused.listing.entity.ListingStatus;
import com.reused.listing.repository.ListingRepository;
import com.reused.trade.dto.request.TradeCloseRequest;
import com.reused.trade.dto.request.TradeCreateRequest;
import com.reused.trade.dto.response.TradeCreateResponse;
import com.reused.trade.dto.response.TradeStatusResponse;
import com.reused.trade.entity.Trade;
import com.reused.trade.entity.TradeStatus;
import com.reused.trade.entity.TradeStatusHistory;
import com.reused.trade.repository.TradeRepository;
import com.reused.trade.repository.TradeStatusHistoryRepository;
import com.reused.user.entity.User;
import com.reused.notification.service.NotificationService;

@Service
public class TradeCommandService {

	private static final EnumSet<TradeStatus> ACTIVE_STATUSES =
			EnumSet.of(TradeStatus.REQUESTED, TradeStatus.ACCEPTED);

	private final TradeUserGuard userGuard;
	private final ListingRepository listingRepository;
	private final TradeRepository tradeRepository;
	private final TradeStatusHistoryRepository historyRepository;
	private final NotificationService notifications;

	public TradeCommandService(TradeUserGuard userGuard, ListingRepository listingRepository,
			TradeRepository tradeRepository, TradeStatusHistoryRepository historyRepository,
			NotificationService notifications) {
		this.userGuard = userGuard;
		this.listingRepository = listingRepository;
		this.tradeRepository = tradeRepository;
		this.historyRepository = historyRepository;
		this.notifications = notifications;
	}

	@Transactional
	public TradeCreateResponse request(AuthPrincipal principal, TradeCreateRequest request) {
		User buyer = userGuard.requireUser(principal, true);
		Listing listing = listingRepository.findActiveByIdForUpdate(request.listingId())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (listing.getSellerId().equals(buyer.getId())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "본인 게시글에는 거래를 요청할 수 없습니다.");
		}
		if (listing.getStatus() != ListingStatus.ON_SALE) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		if (tradeRepository.existsByListingIdAndBuyerIdAndStatusIn(
				listing.getId(), buyer.getId(), ACTIVE_STATUSES)) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}

		Instant now = Instant.now();
		try {
			Trade trade = tradeRepository.saveAndFlush(
					Trade.request(listing.getId(), listing.getSellerId(), buyer.getId(), now));
			historyRepository.save(TradeStatusHistory.changed(trade.getId(), null,
					TradeStatus.REQUESTED, buyer.getId(), null, now));
			notify(trade.getSellerId(), trade, "거래 요청", "새로운 거래 요청이 도착했습니다.");
			return new TradeCreateResponse(trade.getId(), trade.getStatus().name());
		}
		catch (DataIntegrityViolationException e) {
			throw conflict(e);
		}
	}

	@Transactional
	public TradeStatusResponse accept(AuthPrincipal principal, Long tradeId) {
		User seller = userGuard.requireUser(principal, true);
		Trade trade = requireTrade(tradeId);
		requireSeller(trade, seller.getId());
		requireStatus(trade, TradeStatus.REQUESTED);

		Listing listing = lockListing(trade.getListingId());
		if (listing.getStatus() != ListingStatus.ON_SALE) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		Instant changedAt = Instant.now();
		trade.accept(changedAt);
		listing.reserve(changedAt);
		record(trade, TradeStatus.REQUESTED, seller.getId(), null, changedAt);
		flushTransitions();
		notify(trade.getBuyerId(), trade, "거래 승인", "거래 요청이 승인되었습니다.");
		return statusResponse(trade, changedAt);
	}

	@Transactional
	public TradeStatusResponse reject(AuthPrincipal principal, Long tradeId, TradeCloseRequest request) {
		User seller = userGuard.requireUser(principal, false);
		Trade trade = requireTrade(tradeId);
		requireSeller(trade, seller.getId());
		requireStatus(trade, TradeStatus.REQUESTED);

		Instant changedAt = Instant.now();
		trade.reject(seller.getId(), changedAt);
		record(trade, TradeStatus.REQUESTED, seller.getId(), reason(request), changedAt);
		flushTransitions();
		notify(trade.getBuyerId(), trade, "거래 거절", "거래 요청이 거절되었습니다.");
		return statusResponse(trade, changedAt);
	}

	@Transactional
	public TradeStatusResponse cancel(AuthPrincipal principal, Long tradeId, TradeCloseRequest request) {
		User actor = userGuard.requireUser(principal, false);
		Trade trade = requireTrade(tradeId);
		if (!isParty(trade, actor.getId())) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		TradeStatus before = trade.getStatus();
		if (before == TradeStatus.REQUESTED && !trade.getBuyerId().equals(actor.getId())) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		if (before != TradeStatus.REQUESTED && before != TradeStatus.ACCEPTED) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}

		Instant changedAt = Instant.now();
		if (before == TradeStatus.ACCEPTED) {
			Listing listing = lockListing(trade.getListingId());
			if (listing.getStatus() != ListingStatus.RESERVED && listing.getStatus() != ListingStatus.HIDDEN) {
				throw new BusinessException(ErrorCode.CONFLICT);
			}
			// Moderation visibility must survive settlement of the underlying trade.
			if (listing.getStatus() != ListingStatus.HIDDEN) listing.reopen(changedAt);
		}
		trade.cancel(actor.getId(), changedAt);
		record(trade, before, actor.getId(), reason(request), changedAt);
		flushTransitions();
		notify(actor.getId().equals(trade.getSellerId()) ? trade.getBuyerId() : trade.getSellerId(),
				trade, "거래 취소", "거래가 취소되었습니다.");
		return statusResponse(trade, changedAt);
	}

	@Transactional
	public TradeStatusResponse complete(AuthPrincipal principal, Long tradeId) {
		User buyer = userGuard.requireUser(principal, false);
		Trade trade = requireTrade(tradeId);
		if (!trade.getBuyerId().equals(buyer.getId())) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		requireStatus(trade, TradeStatus.ACCEPTED);

		Listing listing = lockListing(trade.getListingId());
		if (listing.getStatus() != ListingStatus.RESERVED && listing.getStatus() != ListingStatus.HIDDEN) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		Instant changedAt = Instant.now();
		trade.complete(changedAt);
		if (listing.getStatus() != ListingStatus.HIDDEN) listing.complete(changedAt);
		record(trade, TradeStatus.ACCEPTED, buyer.getId(), null, changedAt);
		flushTransitions();
		notify(trade.getSellerId(), trade, "거래 완료", "거래가 완료되었습니다. 상대방에게 후기를 남겨주세요.");
		notify(trade.getBuyerId(), trade, "거래 완료", "거래가 완료되었습니다. 상대방에게 후기를 남겨주세요.");
		return statusResponse(trade, changedAt);
	}

	private void notify(Long recipient, Trade trade, String title, String body) {
		notifications.createFor(recipient, "TRADE_" + trade.getStatus().name(), title, body, "TRADE", trade.getId());
	}

	private Trade requireTrade(Long tradeId) {
		return tradeRepository.findById(tradeId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	private Listing lockListing(Long listingId) {
		return listingRepository.findActiveByIdForUpdate(listingId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	private static void requireSeller(Trade trade, Long userId) {
		if (!trade.getSellerId().equals(userId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	private static boolean isParty(Trade trade, Long userId) {
		return trade.getSellerId().equals(userId) || trade.getBuyerId().equals(userId);
	}

	private static void requireStatus(Trade trade, TradeStatus expected) {
		if (trade.getStatus() != expected) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
	}

	private void record(Trade trade, TradeStatus before, Long changedBy, String reason, Instant changedAt) {
		historyRepository.save(TradeStatusHistory.changed(trade.getId(), before,
				trade.getStatus(), changedBy, reason, changedAt));
	}

	private void flushTransitions() {
		try {
			tradeRepository.flush();
			historyRepository.flush();
		}
		catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
			throw conflict(e);
		}
	}

	private static String reason(TradeCloseRequest request) {
		if (request == null || request.reason() == null) {
			return null;
		}
		String reason = request.reason().strip();
		return reason.isEmpty() ? null : reason;
	}

	private static TradeStatusResponse statusResponse(Trade trade, Instant changedAt) {
		return new TradeStatusResponse(trade.getId(), trade.getStatus().name(), changedAt);
	}

	private static BusinessException conflict(RuntimeException cause) {
		return new BusinessException(ErrorCode.CONFLICT, ErrorCode.CONFLICT.defaultMessage(), cause);
	}
}
