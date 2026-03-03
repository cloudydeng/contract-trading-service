package com.matching.contract.dto;

import java.math.BigDecimal;

public record ClosePositionResponse(
        Long tradeId,
        Long positionId,
        String symbol,
        String side,
        BigDecimal closeQuantity,
        BigDecimal closePrice,
        BigDecimal remainingQuantity,
        BigDecimal realizedPnl
) {
}
