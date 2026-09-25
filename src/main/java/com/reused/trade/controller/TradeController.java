package com.reused.trade.controller;

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
import com.reused.listing.query.CursorPageResponse;
import com.reused.trade.dto.request.TradeCloseRequest;
import com.reused.trade.dto.request.TradeCreateRequest;
import com.reused.trade.dto.response.TradeCreateResponse;
import com.reused.trade.dto.response.TradeStatusResponse;
import com.reused.trade.query.TradeDetailResponse;
import com.reused.trade.query.TradeQueryService;
import com.reused.trade.query.TradeSearchRequest;
import com.reused.trade.query.TradeSummaryResponse;
import com.reused.trade.service.TradeCommandService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

	private final TradeCommandService commandService;
	private final TradeQueryService queryService;

	public TradeController(TradeCommandService commandService, TradeQueryService queryService) {
		this.commandService = commandService;
		this.queryService = queryService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TradeCreateResponse request(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody TradeCreateRequest request) {
		return commandService.request(principal, request);
	}

	@PostMapping("/{tradeId}/accept")
	public TradeStatusResponse accept(@AuthUser AuthPrincipal principal, @PathVariable Long tradeId) {
		return commandService.accept(principal, tradeId);
	}

	@PostMapping("/{tradeId}/reject")
	public TradeStatusResponse reject(@AuthUser AuthPrincipal principal, @PathVariable Long tradeId,
			@Valid @RequestBody(required = false) TradeCloseRequest request) {
		return commandService.reject(principal, tradeId, request);
	}

	@PostMapping("/{tradeId}/cancel")
	public TradeStatusResponse cancel(@AuthUser AuthPrincipal principal, @PathVariable Long tradeId,
			@Valid @RequestBody(required = false) TradeCloseRequest request) {
		return commandService.cancel(principal, tradeId, request);
	}

	@PostMapping("/{tradeId}/complete")
	public TradeStatusResponse complete(@AuthUser AuthPrincipal principal, @PathVariable Long tradeId) {
		return commandService.complete(principal, tradeId);
	}

	@GetMapping
	public CursorPageResponse<TradeSummaryResponse> getTrades(
			@AuthUser AuthPrincipal principal,
			@RequestParam(required = false) String role,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return queryService.getTrades(principal, new TradeSearchRequest(role, status, cursor, size));
	}

	@GetMapping("/{tradeId}")
	public TradeDetailResponse getTrade(@AuthUser AuthPrincipal principal, @PathVariable Long tradeId) {
		return queryService.getTrade(principal, tradeId);
	}
}
