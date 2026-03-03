package com.matching.contract.price;

import com.matching.contract.config.PriceEngineProperties;
import com.matching.contract.dto.MarkPriceResponse;
import com.matching.contract.dto.SettlementPriceResponse;
import com.matching.contract.entity.SettlementPriceEntity;
import com.matching.contract.repository.SettlementPriceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SettlementPriceService {

    private final MarkPriceService markPriceService;
    private final SettlementPriceRepository settlementPriceRepository;
    private final PriceEngineProperties properties;
    private final ConcurrentHashMap<String, Deque<Sample>> samplesBySymbol = new ConcurrentHashMap<>();

    public SettlementPriceService(MarkPriceService markPriceService,
                                  SettlementPriceRepository settlementPriceRepository,
                                  PriceEngineProperties properties) {
        this.markPriceService = markPriceService;
        this.settlementPriceRepository = settlementPriceRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${price.sample-interval-ms:5000}")
    public void sampleMarkPrices() {
        Instant now = Instant.now();
        for (String symbol : markPriceService.allSymbols()) {
            markPriceService.getMarkPriceIfReady(symbol).ifPresent(mark -> {
                Deque<Sample> deque = samplesBySymbol.computeIfAbsent(symbol, k -> new ArrayDeque<>());
                synchronized (deque) {
                    deque.addLast(new Sample(now, mark.markPrice(), mark.indexPrice()));
                    trimOld(deque, now);
                }
            });
        }
    }

    public SettlementPriceResponse settleNow(String symbol) {
        MarkPriceResponse mark = markPriceService.getMarkPrice(symbol);
        Deque<Sample> deque = samplesBySymbol.computeIfAbsent(symbol, key -> new ArrayDeque<>());
        BigDecimal settlementPrice;

        synchronized (deque) {
            trimOld(deque, Instant.now());
            if (deque.isEmpty()) {
                settlementPrice = mark.markPrice();
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                for (Sample sample : deque) {
                    sum = sum.add(sample.markPrice());
                }
                settlementPrice = sum.divide(BigDecimal.valueOf(deque.size()), 18, RoundingMode.HALF_UP);
            }
        }

        SettlementPriceEntity entity = new SettlementPriceEntity();
        entity.setSymbol(symbol);
        entity.setIndexPrice(mark.indexPrice());
        entity.setMarkPrice(mark.markPrice());
        entity.setSettlementPrice(settlementPrice);
        entity.setMethod("TWAP_MARK_" + properties.getSettlementWindowSeconds() + "S");
        entity.setSettlementTime(Instant.now());

        SettlementPriceEntity saved = settlementPriceRepository.save(entity);
        return toResponse(saved);
    }

    public List<SettlementPriceResponse> recentSettlements(String symbol) {
        List<SettlementPriceEntity> rows = settlementPriceRepository.findTop20BySymbolOrderBySettlementTimeDesc(symbol);
        List<SettlementPriceResponse> result = new ArrayList<>();
        for (SettlementPriceEntity row : rows) {
            result.add(toResponse(row));
        }
        return result;
    }

    private void trimOld(Deque<Sample> deque, Instant now) {
        Instant cutoff = now.minusSeconds(properties.getSettlementWindowSeconds());
        while (!deque.isEmpty() && deque.peekFirst().timestamp().isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    private SettlementPriceResponse toResponse(SettlementPriceEntity row) {
        return new SettlementPriceResponse(
                row.getSymbol(),
                row.getIndexPrice(),
                row.getMarkPrice(),
                row.getSettlementPrice(),
                row.getMethod(),
                row.getSettlementTime()
        );
    }

    private record Sample(Instant timestamp, BigDecimal markPrice, BigDecimal indexPrice) {
    }
}
