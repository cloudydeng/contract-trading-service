package com.matching.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.matching.contract.dto.OrderResponse;
import com.matching.contract.dto.PlaceOrderRequest;
import com.matching.contract.dto.PlaceOrderResponse;
import com.matching.contract.engine.MatchingEngine;
import com.matching.contract.entity.OrderStatus;
import com.matching.contract.repository.OrderRepository;
import com.matching.contract.repository.PositionRepository;
import com.matching.contract.repository.TradeRepository;
import com.matching.contract.service.OrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MatchingSimulationTests {

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

    private ExecutorService executor;

    @BeforeEach
    void setup() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        orderRepository.deleteAll();
        matchingEngine.reset();
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldMatchBestPriceFirstAcrossLevels() {
        PlaceOrderResponse makerWorsePrice = placeLimit(2001L, "BTCUSDT", "SELL", "1.0", "101");
        PlaceOrderResponse makerBetterPrice = placeLimit(2002L, "BTCUSDT", "SELL", "1.0", "100");

        PlaceOrderResponse taker = placeLimit(1001L, "BTCUSDT", "BUY", "1.0", "101");
        assertEquals(OrderStatus.FILLED, taker.status());

        OrderResponse betterAfter = orderService.getOrder(makerBetterPrice.orderId());
        OrderResponse worseAfter = orderService.getOrder(makerWorsePrice.orderId());

        assertEquals(OrderStatus.FILLED, betterAfter.status());
        assertEquals(0, new BigDecimal("1.0").compareTo(betterAfter.filledQuantity()));
        assertEquals(OrderStatus.NEW, worseAfter.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(worseAfter.filledQuantity()));
    }

    @Test
    void shouldMatchTimePriorityWithinSamePrice() {
        PlaceOrderResponse makerFirst = placeLimit(2101L, "BTCUSDT", "SELL", "0.5", "100");
        PlaceOrderResponse makerSecond = placeLimit(2102L, "BTCUSDT", "SELL", "0.5", "100");

        PlaceOrderResponse taker = placeLimit(1101L, "BTCUSDT", "BUY", "0.7", "100");
        assertEquals(OrderStatus.FILLED, taker.status());

        OrderResponse firstAfter = orderService.getOrder(makerFirst.orderId());
        OrderResponse secondAfter = orderService.getOrder(makerSecond.orderId());

        assertEquals(OrderStatus.FILLED, firstAfter.status());
        assertEquals(0, new BigDecimal("0.5").compareTo(firstAfter.filledQuantity()));
        assertEquals(OrderStatus.PARTIALLY_FILLED, secondAfter.status());
        assertEquals(0, new BigDecimal("0.2").compareTo(secondAfter.filledQuantity()));
    }

    @Test
    void shouldKeepConsistencyUnderConcurrentFlowSingleSymbol() throws ExecutionException, InterruptedException {
        for (int i = 0; i < 10; i++) {
            placeLimit(3000L + i, "BTCUSDT", "SELL", "1.0", "100");
        }

        List<Callable<PlaceOrderResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long uid = 4000L + i;
            tasks.add(() -> placeLimit(uid, "BTCUSDT", "BUY", "0.5", "100"));
        }

        List<Future<PlaceOrderResponse>> futures = executor.invokeAll(tasks);
        BigDecimal totalFilled = BigDecimal.ZERO;

        for (Future<PlaceOrderResponse> future : futures) {
            PlaceOrderResponse response = future.get();
            assertTrue(response.status() == OrderStatus.FILLED || response.status() == OrderStatus.PARTIALLY_FILLED);
            OrderResponse order = orderService.getOrder(response.orderId());
            totalFilled = totalFilled.add(order.filledQuantity());
        }

        assertEquals(0, new BigDecimal("10.0").compareTo(totalFilled));
    }

    private PlaceOrderResponse placeLimit(Long userId, String symbol, String side, String quantity, String price) {
        return orderService.placeOrder(new PlaceOrderRequest(
                userId,
                symbol,
                side,
                "LIMIT",
                "GTC",
                new BigDecimal(quantity),
                new BigDecimal(price),
                false
        ));
    }
}
