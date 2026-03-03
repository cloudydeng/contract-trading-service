package com.matching.contract.dto;

import com.matching.contract.entity.LiquidityRole;
import com.matching.contract.entity.TradeEntity;
import java.math.BigDecimal;
import java.time.Instant;

public record TradeResponse(
        Long tradeId,
        Long orderId,
        Long userId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        LiquidityRole liquidityRole,
        BigDecimal fee,
        BigDecimal realizedPnl,
        Boolean closeTrade,
        Instant createdAt
) {
    public static TradeResponse from(TradeEntity trade) {
        return new TradeResponse(
                trade.getId(),
                trade.getOrderId(),
                trade.getUserId(),
                trade.getSymbol(),
                trade.getSide(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getLiquidityRole(),
                trade.getFee(),
                trade.getRealizedPnl(),
                trade.getCloseTrade(),
                trade.getCreatedAt()
        );
    }
}
