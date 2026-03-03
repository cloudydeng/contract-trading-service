package com.matching.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.matching.contract.dto.OrderResponse;
import com.matching.contract.dto.PlaceOrderRequest;
import com.matching.contract.dto.PlaceOrderResponse;
import com.matching.contract.engine.MatchingEngine;
import com.matching.contract.entity.LiquidityRole;
import com.matching.contract.entity.OrderStatus;
import com.matching.contract.entity.TradeEntity;
import com.matching.contract.repository.OrderRepository;
import com.matching.contract.repository.PositionRepository;
import com.matching.contract.repository.TradeRepository;
import com.matching.contract.service.OrderService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MatchingEngineFlowTests {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private MatchingEngine matchingEngine;

    @BeforeEach
    void setup() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        orderRepository.deleteAll();
        matchingEngine.reset();
    }

    @Test
    void shouldMatchPartiallyAndKeepRemainderOnBook() {
        PlaceOrderResponse makerSell = orderService.placeOrder(new PlaceOrderRequest(
                2001L,
                "BTCUSDT",
                "SELL",
                "LIMIT",
                "GTC",
                new BigDecimal("1.0"),
                new BigDecimal("100"),
                false
        ));
        assertEquals(OrderStatus.NEW, makerSell.status());

        PlaceOrderResponse takerBuy = orderService.placeOrder(new PlaceOrderRequest(
                1001L,
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                new BigDecimal("0.4"),
                new BigDecimal("110"),
                false
        ));
        assertEquals(OrderStatus.FILLED, takerBuy.status());

        OrderResponse makerAfterFirstMatch = orderService.getOrder(makerSell.orderId());
        assertEquals(OrderStatus.PARTIALLY_FILLED, makerAfterFirstMatch.status());
        assertEquals(0, new BigDecimal("0.4").compareTo(makerAfterFirstMatch.filledQuantity()));

        PlaceOrderResponse nextTakerBuy = orderService.placeOrder(new PlaceOrderRequest(
                3001L,
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                new BigDecimal("0.8"),
                new BigDecimal("100"),
                false
        ));
        assertEquals(OrderStatus.PARTIALLY_FILLED, nextTakerBuy.status());

        OrderResponse makerAfterSecondMatch = orderService.getOrder(makerSell.orderId());
        assertEquals(OrderStatus.FILLED, makerAfterSecondMatch.status());
        assertEquals(0, new BigDecimal("1.0").compareTo(makerAfterSecondMatch.filledQuantity()));
    }

    @Test
    void shouldCancelIocRemainderAndKeepFilledQuantity() {
        orderService.placeOrder(new PlaceOrderRequest(
                2001L, "BTCUSDT", "SELL", "LIMIT", "GTC",
                new BigDecimal("0.5"), new BigDecimal("100"), false
        ));

        PlaceOrderResponse iocBuy = orderService.placeOrder(new PlaceOrderRequest(
                1001L, "BTCUSDT", "BUY", "LIMIT", "IOC",
                new BigDecimal("1.0"), new BigDecimal("100"), false
        ));
        assertEquals(OrderStatus.CANCELED, iocBuy.status());

        OrderResponse iocOrder = orderService.getOrder(iocBuy.orderId());
        assertEquals(0, new BigDecimal("0.5").compareTo(iocOrder.filledQuantity()));
    }

    @Test
    void shouldCancelFokWithoutAnyFillWhenLiquidityInsufficient() {
        PlaceOrderResponse makerSell = orderService.placeOrder(new PlaceOrderRequest(
                2001L, "BTCUSDT", "SELL", "LIMIT", "GTC",
                new BigDecimal("0.5"), new BigDecimal("100"), false
        ));

        PlaceOrderResponse fokBuy = orderService.placeOrder(new PlaceOrderRequest(
                1001L, "BTCUSDT", "BUY", "LIMIT", "FOK",
                new BigDecimal("1.0"), new BigDecimal("100"), false
        ));
        assertEquals(OrderStatus.CANCELED, fokBuy.status());

        OrderResponse makerAfter = orderService.getOrder(makerSell.orderId());
        assertEquals(OrderStatus.NEW, makerAfter.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(makerAfter.filledQuantity()));
    }

    @Test
    void shouldPersistMakerTakerFeeOnMatchedTrades() {
        PlaceOrderResponse makerSell = orderService.placeOrder(new PlaceOrderRequest(
                2001L, "BTCUSDT", "SELL", "LIMIT", "GTC",
                new BigDecimal("1.0"), new BigDecimal("100"), false
        ));
        PlaceOrderResponse takerBuy = orderService.placeOrder(new PlaceOrderRequest(
                1001L, "BTCUSDT", "BUY", "LIMIT", "GTC",
                new BigDecimal("1.0"), new BigDecimal("100"), false
        ));

        TradeEntity makerTrade = tradeRepository.findByOrderIdOrderByCreatedAtDesc(makerSell.orderId()).getFirst();
        TradeEntity takerTrade = tradeRepository.findByOrderIdOrderByCreatedAtDesc(takerBuy.orderId()).getFirst();

        assertEquals(LiquidityRole.MAKER, makerTrade.getLiquidityRole());
        assertEquals(LiquidityRole.TAKER, takerTrade.getLiquidityRole());
        assertTrue(makerTrade.getFee().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(takerTrade.getFee().compareTo(BigDecimal.ZERO) > 0);
    }
}
