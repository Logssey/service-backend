package com.reused.trade.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TradeCreateRequest(@NotNull @Positive Long listingId) {
}
