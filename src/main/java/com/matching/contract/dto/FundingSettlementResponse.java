package com.matching.contract.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FundingSettlementResponse(
        Long userId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal rate,
        BigDecimal amount,
        Instant fundingTime
) {
}
