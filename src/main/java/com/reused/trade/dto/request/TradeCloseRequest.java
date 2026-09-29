package com.reused.trade.dto.request;

import jakarta.validation.constraints.Size;

public record TradeCloseRequest(@Size(max = 500) String reason) {
}
