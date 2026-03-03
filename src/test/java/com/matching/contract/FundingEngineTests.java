package com.matching.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.matching.contract.dto.FundingRateResponse;
import com.matching.contract.dto.PlaceOrderRequest;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.repository.FundingSettlementRepository;
import com.matching.contract.repository.OrderRepository;
import com.matching.contract.repository.PositionRepository;
import com.matching.contract.repository.TradeRepository;
import com.matching.contract.service.FundingService;
import com.matching.contract.service.OrderService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FundingEngineTests {

    @Autowired
    private OrderService orderService;

    @Autowired
    private FundingService fundingService;

    @Autowired
    private MarkPriceService markPriceService;

    @Autowired
    private FundingSettlementRepository fundingSettlementRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @BeforeEach
    void setup() {
        fundingSettlementRepository.deleteAll();
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void shouldSettleFundingAndGenerateSettlements() {
        markPriceService.updateIndexPrice("BTCUSDT", Map.of(
                "a", new BigDecimal("100"),
                "b", new BigDecimal("100"),
                "c", new BigDecimal("100")
        ));

        orderService.placeOrder(new PlaceOrderRequest(
                7001L, "BTCUSDT", "BUY", "LIMIT", "GTC",
                new BigDecimal("1"), new BigDecimal("100"), false
        ));
        orderService.placeOrder(new PlaceOrderRequest(
                7002L, "BTCUSDT", "SELL", "LIMIT", "GTC",
                new BigDecimal("1"), new BigDecimal("100"), false
        ));

        markPriceService.updateLastTradePrice("BTCUSDT", new BigDecimal("101"));
        FundingRateResponse rate = fundingService.settleNow("BTCUSDT");

        assertEquals("BTCUSDT", rate.symbol());
        assertTrue(rate.rate().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(fundingSettlementRepository.findTop50BySymbolOrderByFundingTimeDesc("BTCUSDT").size() >= 2);
    }
}
