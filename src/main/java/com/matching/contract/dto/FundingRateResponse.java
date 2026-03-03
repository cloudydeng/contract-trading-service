package com.matching.contract.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FundingRateResponse(
        String symbol,
        BigDecimal indexPrice,
        BigDecimal markPrice,
        BigDecimal rate,
        Instant fundingTime
) {
}
