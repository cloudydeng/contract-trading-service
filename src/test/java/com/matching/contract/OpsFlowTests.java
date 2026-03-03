package com.matching.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.matching.contract.dto.ClosePositionMarketRequest;
import com.matching.contract.dto.PlaceOrderRequest;
import com.matching.contract.entity.OrderStatus;
import com.matching.contract.entity.OutboxEventEntity;
import com.matching.contract.persistence.OutboxPublisherService;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.repository.OrderRepository;
import com.matching.contract.repository.OutboxEventRepository;
import com.matching.contract.repository.PositionRepository;
import com.matching.contract.repository.TradeRepository;
import com.matching.contract.service.OrderService;
import com.matching.contract.service.LiquidationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OpsFlowTests {

    @Autowired
    private OrderService orderService;

    @Autowired
    private MarkPriceService markPriceService;

    @Autowired
    private LiquidationService liquidationService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @BeforeEach
    void setup() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        orderRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldRetryAndPublishOutboxEvent() {
        OutboxEventEntity failed = new OutboxEventEntity();
        failed.setEventId("evt-fail-1");
        failed.setTopic("simulate.fail.test");
        failed.setPayload("x");
        failed.setStatus("NEW");
        failed.setRetryCount(0);
        failed.setCreatedAt(Instant.now());
        outboxEventRepository.save(failed);

        OutboxEventEntity ok = new OutboxEventEntity();
        ok.setEventId("evt-ok-1");
        ok.setTopic("trade.executed");
        ok.setPayload("y");
        ok.setStatus("NEW");
        ok.setRetryCount(0);
        ok.setCreatedAt(Instant.now());
        outboxEventRepository.save(ok);

        outboxPublisherService.publishBatch();

        OutboxEventEntity failedAfter = outboxEventRepository.findById(failed.getId()).orElseThrow();
        OutboxEventEntity okAfter = outboxEventRepository.findById(ok.getId()).orElseThrow();

        assertEquals("FAILED", failedAfter.getStatus());
        assertTrue(failedAfter.getRetryCount() >= 1);
        assertEquals("PUBLISHED", okAfter.getStatus());
        assertTrue(okAfter.getPublishedAt() != null);
    }

    @Test
    void shouldLiquidateWhenLossExceedsThreshold() {
        markPriceService.updateIndexPrice("BTCUSDT", Map.of("a", new BigDecimal("100"), "b", new BigDecimal("100"), "c", new BigDecimal("100")));

        // Open long at 100
        orderService.placeOrder(new PlaceOrderRequest(
                9001L,
                "BTCUSDT",
                "BUY",
                "LIMIT",
                "GTC",
                new BigDecimal("1.0"),
                new BigDecimal("100"),
                false
        ));

        // Crash mark to 10 -> huge loss, should trigger liquidation close.
        markPriceService.updateIndexPrice("BTCUSDT", Map.of("a", new BigDecimal("10"), "b", new BigDecimal("10"), "c", new BigDecimal("10")));
        liquidationService.scanAndLiquidate();

        var positions = positionRepository.findByUserIdAndSymbolAndQuantityGreaterThan(9001L, "BTCUSDT", BigDecimal.ZERO);
        assertTrue(positions.isEmpty());
    }
}
