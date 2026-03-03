package com.matching.contract.service;

import com.matching.contract.dto.PlaceOrderRequest;
import com.matching.contract.entity.OrderType;
import com.matching.contract.entity.PositionEntity;
import com.matching.contract.entity.PositionSide;
import com.matching.contract.entity.TimeInForce;
import com.matching.contract.exception.BadRequestException;
import com.matching.contract.repository.PositionRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class RiskService {

    private final PositionRepository positionRepository;

    public RiskService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public void preCheck(PlaceOrderRequest request) {
        String side = request.side().toUpperCase();
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new BadRequestException("side must be BUY or SELL");
        }
        OrderType orderType = parseOrderType(request.orderType());
        TimeInForce timeInForce = parseTimeInForce(request.timeInForce());
        if (orderType == OrderType.MARKET && timeInForce == TimeInForce.GTC) {
            throw new BadRequestException("MARKET order does not support GTC");
        }
        if (orderType == OrderType.LIMIT && request.price() == null) {
            throw new BadRequestException("LIMIT order requires price");
        }
        if (request.reduceOnly()) {
            PositionSide positionSideToReduce = "SELL".equals(side) ? PositionSide.LONG : PositionSide.SHORT;
            PositionEntity position = positionRepository
                    .findByUserIdAndSymbolAndSide(request.userId(), request.symbol(), positionSideToReduce)
                    .orElseThrow(() -> new BadRequestException("reduce-only order requires an opposite open position"));
            if (position.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("reduce-only order requires an opposite open position");
            }
            if (request.quantity().compareTo(position.getQuantity()) > 0) {
                throw new BadRequestException("reduce-only quantity exceeds current position");
            }
        }
    }

    private OrderType parseOrderType(String orderType) {
        try {
            return OrderType.valueOf(orderType.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("orderType must be LIMIT or MARKET");
        }
    }

    private TimeInForce parseTimeInForce(String timeInForce) {
        try {
            return TimeInForce.valueOf(timeInForce.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("timeInForce must be GTC, IOC or FOK");
        }
    }
}
