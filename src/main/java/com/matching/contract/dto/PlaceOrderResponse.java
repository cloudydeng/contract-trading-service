package com.matching.contract.dto;

import com.matching.contract.entity.OrderStatus;

public record PlaceOrderResponse(Long orderId, OrderStatus status) {
}
