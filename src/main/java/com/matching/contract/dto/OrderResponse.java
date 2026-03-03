package com.matching.contract.dto;

import com.matching.contract.entity.OrderEntity;
import com.matching.contract.entity.OrderStatus;
import com.matching.contract.entity.OrderType;
import com.matching.contract.entity.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long orderId,
        Long userId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        BigDecimal price,
        Boolean reduceOnly,
        OrderType orderType,
        TimeInForce timeInForce,
        OrderStatus status,
        Instant createdAt
) {
    public static OrderResponse from(OrderEntity order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.getPrice(),
                order.getReduceOnly(),
                order.getOrderType(),
                order.getTimeInForce(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
