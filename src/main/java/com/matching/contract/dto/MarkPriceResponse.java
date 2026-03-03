package com.matching.contract.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MarkPriceResponse(
        String symbol,
        BigDecimal indexPrice,
        BigDecimal lastTradePrice,
        BigDecimal markPrice,
        Instant updatedAt
) {
}
