package com.matching.contract.service;

import com.matching.contract.config.FundingProperties;
import com.matching.contract.dto.FundingRateResponse;
import com.matching.contract.dto.FundingSettlementResponse;
import com.matching.contract.dto.MarkPriceResponse;
import com.matching.contract.entity.FundingRateEntity;
import com.matching.contract.entity.FundingSettlementEntity;
import com.matching.contract.entity.PositionEntity;
import com.matching.contract.entity.PositionSide;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.repository.FundingRateRepository;
import com.matching.contract.repository.FundingSettlementRepository;
import com.matching.contract.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundingService {

    private final FundingProperties properties;
    private final MarkPriceService markPriceService;
    private final PositionRepository positionRepository;
    private final FundingRateRepository fundingRateRepository;
    private final FundingSettlementRepository fundingSettlementRepository;
    private final LedgerService ledgerService;

    public FundingService(FundingProperties properties,
                          MarkPriceService markPriceService,
                          PositionRepository positionRepository,
                          FundingRateRepository fundingRateRepository,
                          FundingSettlementRepository fundingSettlementRepository,
                          LedgerService ledgerService) {
        this.properties = properties;
        this.markPriceService = markPriceService;
        this.positionRepository = positionRepository;
        this.fundingRateRepository = fundingRateRepository;
        this.fundingSettlementRepository = fundingSettlementRepository;
        this.ledgerService = ledgerService;
    }

    @Scheduled(fixedDelayString = "${funding.scan-interval-ms:10000}")
    @Transactional
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (String symbol : markPriceService.allSymbols()) {
            markPriceService.getMarkPriceIfReady(symbol).ifPresent(mark -> {
                Instant fundingTime = alignFundingTime(now);
                if (fundingRateRepository.existsBySymbolAndFundingTime(symbol, fundingTime)) {
                    return;
                }
                if (!isFundingMoment(now)) {
                    return;
                }

                FundingRateEntity rate = computeRate(symbol, mark, fundingTime);
                fundingRateRepository.save(rate);
                settleFunding(rate);
            });
        }
    }

    @Transactional
    public FundingRateResponse settleNow(String symbol) {
        MarkPriceResponse mark = markPriceService.getMarkPrice(symbol);
        Instant fundingTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        FundingRateEntity rate = computeRate(symbol, mark, fundingTime);
        fundingRateRepository.save(rate);
        settleFunding(rate);
        return toRateResponse(rate);
    }

    @Transactional(readOnly = true)
    public List<FundingRateResponse> recentRates(String symbol) {
        List<FundingRateEntity> rows = fundingRateRepository.findTop20BySymbolOrderByFundingTimeDesc(symbol);
        List<FundingRateResponse> result = new ArrayList<>();
        for (FundingRateEntity row : rows) {
            result.add(toRateResponse(row));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<FundingSettlementResponse> recentSettlements(String symbol) {
        List<FundingSettlementEntity> rows = fundingSettlementRepository.findTop50BySymbolOrderByFundingTimeDesc(symbol);
        List<FundingSettlementResponse> result = new ArrayList<>();
        for (FundingSettlementEntity row : rows) {
            result.add(new FundingSettlementResponse(
                    row.getUserId(),
                    row.getSymbol(),
                    row.getSide(),
                    row.getQuantity(),
                    row.getRate(),
                    row.getAmount(),
                    row.getFundingTime()
            ));
        }
        return result;
    }

    private void settleFunding(FundingRateEntity rate) {
        List<PositionEntity> positions = positionRepository.findByQuantityGreaterThan(BigDecimal.ZERO);
        for (PositionEntity position : positions) {
            if (!position.getSymbol().equals(rate.getSymbol())) {
                continue;
            }
            if (fundingSettlementRepository.existsByUserIdAndSymbolAndFundingTime(
                    position.getUserId(), position.getSymbol(), rate.getFundingTime())) {
                continue;
            }

            BigDecimal notional = position.getQuantity().multiply(rate.getMarkPrice());
            BigDecimal gross = notional.multiply(rate.getRate());
            BigDecimal amount;
            if (position.getSide() == PositionSide.LONG) {
                amount = gross.negate();
            } else {
                amount = gross;
            }

            FundingSettlementEntity settlement = new FundingSettlementEntity();
            settlement.setUserId(position.getUserId());
            settlement.setSymbol(position.getSymbol());
            settlement.setSide(position.getSide().name());
            settlement.setQuantity(position.getQuantity());
            settlement.setRate(rate.getRate());
            settlement.setAmount(amount);
            settlement.setFundingTime(rate.getFundingTime());
            FundingSettlementEntity saved = fundingSettlementRepository.save(settlement);

            ledgerService.settleFunding(position.getUserId(), saved.getId(), amount);
        }
    }

    private FundingRateEntity computeRate(String symbol, MarkPriceResponse mark, Instant fundingTime) {
        BigDecimal premium = mark.markPrice().subtract(mark.indexPrice())
                .divide(mark.indexPrice(), 18, RoundingMode.HALF_UP);
        BigDecimal maxRate = BigDecimal.valueOf(properties.getMaxRateBps())
                .divide(BigDecimal.valueOf(10000), 18, RoundingMode.HALF_UP);
        BigDecimal clamped = premium.max(maxRate.negate()).min(maxRate);

        FundingRateEntity rate = new FundingRateEntity();
        rate.setSymbol(symbol);
        rate.setIndexPrice(mark.indexPrice());
        rate.setMarkPrice(mark.markPrice());
        rate.setRate(clamped);
        rate.setFundingTime(fundingTime);
        return rate;
    }

    private boolean isFundingMoment(Instant now) {
        long hour = now.atZone(java.time.ZoneOffset.UTC).getHour();
        return hour % properties.getIntervalHours() == 0 && now.atZone(java.time.ZoneOffset.UTC).getMinute() == 0
                && now.atZone(java.time.ZoneOffset.UTC).getSecond() <= properties.getSettleDelaySeconds();
    }

    private Instant alignFundingTime(Instant now) {
        var z = now.atZone(java.time.ZoneOffset.UTC);
        int slot = (z.getHour() / properties.getIntervalHours()) * properties.getIntervalHours();
        return z.withHour(slot).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    private FundingRateResponse toRateResponse(FundingRateEntity row) {
        return new FundingRateResponse(
                row.getSymbol(),
                row.getIndexPrice(),
                row.getMarkPrice(),
                row.getRate(),
                row.getFundingTime()
        );
    }
}
