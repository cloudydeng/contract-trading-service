package com.matching.contract.service;

import com.matching.contract.config.LiquidationProperties;
import com.matching.contract.dto.ClosePositionMarketRequest;
import com.matching.contract.entity.PositionEntity;
import com.matching.contract.entity.PositionSide;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LiquidationService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationService.class);

    private final PositionRepository positionRepository;
    private final MarkPriceService markPriceService;
    private final PositionService positionService;
    private final LiquidationProperties properties;

    public LiquidationService(PositionRepository positionRepository,
                              MarkPriceService markPriceService,
                              PositionService positionService,
                              LiquidationProperties properties) {
        this.positionRepository = positionRepository;
        this.markPriceService = markPriceService;
        this.positionService = positionService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${liquidation.scan-interval-ms:2000}")
    public void scanAndLiquidate() {
        if (!properties.isEnabled()) {
            return;
        }

        List<PositionEntity> positions = positionRepository.findByQuantityGreaterThan(BigDecimal.ZERO);
        for (PositionEntity position : positions) {
            markPriceService.getMarkPriceIfReady(position.getSymbol()).ifPresent(mark -> {
                BigDecimal lossRatioBps = estimateLossRatioBps(position, mark.markPrice());
                if (lossRatioBps.compareTo(BigDecimal.valueOf(properties.getTriggerLossBps())) >= 0) {
                    try {
                        positionService.closeMarket(position.getSymbol(), new ClosePositionMarketRequest(
                                position.getUserId(),
                                position.getQuantity(),
                                mark.markPrice()
                        ));
                        log.warn("liquidated user={} symbol={} side={} qty={} markPrice={} lossBps={}",
                                position.getUserId(), position.getSymbol(), position.getSide(), position.getQuantity(),
                                mark.markPrice(), lossRatioBps);
                    } catch (Exception ex) {
                        log.error("liquidation failed user={} symbol={} err={}",
                                position.getUserId(), position.getSymbol(), ex.getMessage());
                    }
                }
            });
        }
    }

    private BigDecimal estimateLossRatioBps(PositionEntity position, BigDecimal markPrice) {
        BigDecimal pnl;
        if (position.getSide() == PositionSide.LONG) {
            pnl = markPrice.subtract(position.getEntryPrice()).multiply(position.getQuantity());
        } else {
            pnl = position.getEntryPrice().subtract(markPrice).multiply(position.getQuantity());
        }
        BigDecimal notional = position.getEntryPrice().multiply(position.getQuantity());
        if (notional.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal loss = pnl.min(BigDecimal.ZERO).negate();
        return loss.multiply(BigDecimal.valueOf(10000)).divide(notional, 4, RoundingMode.HALF_UP);
    }
}
