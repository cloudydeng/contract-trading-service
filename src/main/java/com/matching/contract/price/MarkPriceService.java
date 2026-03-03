package com.matching.contract.price;

import com.matching.contract.config.PriceEngineProperties;
import com.matching.contract.dto.MarkPriceResponse;
import com.matching.contract.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class MarkPriceService {

    private final PriceEngineProperties properties;
    private final ConcurrentHashMap<String, PriceState> stateBySymbol = new ConcurrentHashMap<>();

    public MarkPriceService(PriceEngineProperties properties) {
        this.properties = properties;
    }

    public MarkPriceResponse updateIndexPrice(String symbol, Map<String, BigDecimal> sources) {
        BigDecimal indexPrice = median(sources);
        PriceState state = stateBySymbol.computeIfAbsent(symbol, key -> new PriceState());
        synchronized (state) {
            state.indexPrice = indexPrice;
            state.updatedAt = Instant.now();
            state.markPrice = computeMark(state.indexPrice, state.lastTradePrice);
            return snapshot(symbol, state);
        }
    }

    public void updateLastTradePrice(String symbol, BigDecimal lastTradePrice) {
        PriceState state = stateBySymbol.computeIfAbsent(symbol, key -> new PriceState());
        synchronized (state) {
            state.lastTradePrice = lastTradePrice;
            state.updatedAt = Instant.now();
            if (state.indexPrice != null) {
                state.markPrice = computeMark(state.indexPrice, state.lastTradePrice);
            }
        }
    }

    public MarkPriceResponse getMarkPrice(String symbol) {
        PriceState state = stateBySymbol.get(symbol);
        if (state == null || state.indexPrice == null || state.markPrice == null) {
            throw new BadRequestException("mark price unavailable: index price not initialized for symbol " + symbol);
        }
        synchronized (state) {
            return snapshot(symbol, state);
        }
    }

    public Optional<MarkPriceResponse> getMarkPriceIfReady(String symbol) {
        PriceState state = stateBySymbol.get(symbol);
        if (state == null || state.indexPrice == null || state.markPrice == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.of(snapshot(symbol, state));
        }
    }

    public List<String> allSymbols() {
        return new ArrayList<>(stateBySymbol.keySet());
    }

    private MarkPriceResponse snapshot(String symbol, PriceState state) {
        return new MarkPriceResponse(symbol, state.indexPrice, state.lastTradePrice, state.markPrice, state.updatedAt);
    }

    private BigDecimal computeMark(BigDecimal indexPrice, BigDecimal lastTradePrice) {
        if (lastTradePrice == null) {
            return indexPrice;
        }
        BigDecimal basis = lastTradePrice.subtract(indexPrice);
        BigDecimal maxBasis = indexPrice.multiply(BigDecimal.valueOf(properties.getMarkBasisLimitBps()))
                .divide(BigDecimal.valueOf(10000), 18, RoundingMode.HALF_UP);
        BigDecimal clampedBasis = basis.max(maxBasis.negate()).min(maxBasis);
        return indexPrice.add(clampedBasis);
    }

    private BigDecimal median(Map<String, BigDecimal> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new BadRequestException("index price sources must not be empty");
        }
        List<BigDecimal> values = new ArrayList<>(sources.values());
        Collections.sort(values);
        int n = values.size();
        if (n % 2 == 1) {
            return values.get(n / 2);
        }
        return values.get(n / 2 - 1).add(values.get(n / 2))
                .divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
    }

    private static final class PriceState {
        private BigDecimal indexPrice;
        private BigDecimal lastTradePrice;
        private BigDecimal markPrice;
        private Instant updatedAt;
    }
}
