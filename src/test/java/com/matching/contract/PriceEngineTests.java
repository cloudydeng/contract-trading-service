package com.matching.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.matching.contract.dto.MarkPriceResponse;
import com.matching.contract.dto.SettlementPriceResponse;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.price.SettlementPriceService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PriceEngineTests {

    @Autowired
    private MarkPriceService markPriceService;

    @Autowired
    private SettlementPriceService settlementPriceService;

    @Test
    void shouldComputeMarkPriceWithBasisClamp() {
        markPriceService.updateIndexPrice("BTCUSDT", Map.of(
                "binance", new BigDecimal("100"),
                "okx", new BigDecimal("100"),
                "bybit", new BigDecimal("100")
        ));

        markPriceService.updateLastTradePrice("BTCUSDT", new BigDecimal("200"));
        MarkPriceResponse mark = markPriceService.getMarkPrice("BTCUSDT");

        assertEquals(0, new BigDecimal("100.3").compareTo(mark.markPrice()));
    }

    @Test
    void shouldGenerateSettlementPrice() {
        markPriceService.updateIndexPrice("ETHUSDT", Map.of(
                "binance", new BigDecimal("2000"),
                "okx", new BigDecimal("2001"),
                "bybit", new BigDecimal("1999")
        ));
        markPriceService.updateLastTradePrice("ETHUSDT", new BigDecimal("2002"));

        settlementPriceService.sampleMarkPrices();
        SettlementPriceResponse settlement = settlementPriceService.settleNow("ETHUSDT");

        assertEquals("ETHUSDT", settlement.symbol());
        assertTrue(settlement.settlementPrice().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(settlement.method().startsWith("TWAP_MARK_"));
    }
}
