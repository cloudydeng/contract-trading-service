package com.matching.contract.service;

import com.matching.contract.dto.PlaceOrderRequest;
import com.matching.contract.dto.PlaceOrderResponse;
import com.matching.contract.dto.OrderResponse;
import com.matching.contract.engine.MatchingEngine;
import com.matching.contract.entity.OrderEntity;
import com.matching.contract.entity.OrderStatus;
import com.matching.contract.entity.OrderType;
import com.matching.contract.entity.TimeInForce;
import com.matching.contract.exception.ConflictException;
import com.matching.contract.exception.NotFoundException;
import com.matching.contract.repository.OrderRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final RiskService riskService;
    private final LedgerService ledgerService;
    private final OrderRepository orderRepository;
    private final MatchingEngine matchingEngine;

    public OrderService(RiskService riskService,
                        LedgerService ledgerService,
                        OrderRepository orderRepository,
                        MatchingEngine matchingEngine) {
        this.riskService = riskService;
        this.ledgerService = ledgerService;
        this.orderRepository = orderRepository;
        this.matchingEngine = matchingEngine;
    }

    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
        riskService.preCheck(request);

        OrderEntity order = new OrderEntity();
        order.setUserId(request.userId());
        order.setSymbol(request.symbol());
        order.setSide(request.side().toUpperCase());
        order.setOrderType(OrderType.valueOf(request.orderType().toUpperCase()));
        order.setTimeInForce(TimeInForce.valueOf(request.timeInForce().toUpperCase()));
        order.setQuantity(request.quantity());
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setPrice(request.price() == null ? BigDecimal.ZERO : request.price());
        order.setReduceOnly(request.reduceOnly());
        order.setStatus(OrderStatus.NEW);

        OrderEntity saved = orderRepository.save(order);
        ledgerService.reserveMargin(saved);
        OrderEntity matched = matchingEngine.submit(saved);

        return new PlaceOrderResponse(matched.getId(), matched.getStatus());
    }

    public OrderResponse getOrder(Long orderId) {
        OrderEntity order = findById(orderId);
        return OrderResponse.from(order);
    }

    public OrderResponse cancelOrder(Long orderId) {
        OrderEntity order = findById(orderId);
        if (!isCancelable(order.getStatus())) {
            throw new ConflictException("order cannot be canceled in status " + order.getStatus());
        }

        order.setStatus(OrderStatus.CANCELED);
        OrderEntity saved = orderRepository.save(order);
        matchingEngine.cancel(saved);
        ledgerService.releaseMargin(saved);
        return OrderResponse.from(saved);
    }

    private OrderEntity findById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("order not found: " + orderId));
    }

    private boolean isCancelable(OrderStatus status) {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }
}
