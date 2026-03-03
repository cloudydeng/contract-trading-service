package com.matching.contract.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SettlementPriceResponse(
        String symbol,
        BigDecimal indexPrice,
        BigDecimal markPrice,
        BigDecimal settlementPrice,
        String method,
        Instant settlementTime
) {
}
