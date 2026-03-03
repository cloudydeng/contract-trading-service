package com.matching.contract.service;

import com.matching.contract.dto.ClosePositionMarketRequest;
import com.matching.contract.dto.ClosePositionResponse;
import com.matching.contract.entity.LiquidityRole;
import com.matching.contract.entity.PositionEntity;
import com.matching.contract.entity.PositionSide;
import com.matching.contract.entity.TradeEntity;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.exception.BadRequestException;
import com.matching.contract.repository.PositionRepository;
import com.matching.contract.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionService {

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final MarkPriceService markPriceService;
    private final LedgerService ledgerService;

    public PositionService(PositionRepository positionRepository,
                           TradeRepository tradeRepository,
                           MarkPriceService markPriceService,
                           LedgerService ledgerService) {
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
        this.markPriceService = markPriceService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public ExecutionResult applyFill(Long userId,
                                     Long orderId,
                                     String symbol,
                                     String orderSide,
                                     BigDecimal quantity,
                                     BigDecimal fillPrice,
                                     boolean reduceOnly,
                                     LiquidityRole liquidityRole,
                                     BigDecimal feeRate) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("fill quantity must be positive");
        }

        PositionSide incomingSide = toPositionSide(orderSide);
        PositionSide oppositeSide = incomingSide == PositionSide.LONG ? PositionSide.SHORT : PositionSide.LONG;

        PositionEntity sameSidePosition = findPosition(userId, symbol, incomingSide).orElse(null);
        PositionEntity oppositePosition = findPosition(userId, symbol, oppositeSide).orElse(null);

        if (hasOpenPosition(sameSidePosition) && hasOpenPosition(oppositePosition)) {
            throw new BadRequestException("hedge mode positions are not supported in V1");
        }

        BigDecimal remaining = quantity;
        BigDecimal realizedPnlTotal = BigDecimal.ZERO;
        TradeEntity firstTrade = null;

        if (hasOpenPosition(oppositePosition)) {
            BigDecimal closeQuantity = remaining.min(oppositePosition.getQuantity());
            BigDecimal realizedPnl = calculateRealizedPnl(oppositePosition, closeQuantity, fillPrice);
            realizedPnlTotal = realizedPnlTotal.add(realizedPnl);

            oppositePosition.setQuantity(oppositePosition.getQuantity().subtract(closeQuantity));
            if (oppositePosition.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                oppositePosition.setEntryPrice(BigDecimal.ZERO);
            }
            positionRepository.save(oppositePosition);

            TradeEntity closeTrade = persistTrade(
                    userId,
                    orderId,
                    symbol,
                    orderSide,
                    closeQuantity,
                    fillPrice,
                    realizedPnl,
                    true,
                    liquidityRole,
                    feeRate
            );
            ledgerService.settleTrade(userId, closeTrade.getId(), closeTrade.getRealizedPnl(), closeTrade.getFee());
            firstTrade = closeTrade;
            remaining = remaining.subtract(closeQuantity);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            if (reduceOnly) {
                throw new BadRequestException("reduce-only order cannot increase or open position");
            }

            PositionEntity target = sameSidePosition;
            if (target == null) {
                target = new PositionEntity();
                target.setUserId(userId);
                target.setSymbol(symbol);
                target.setSide(incomingSide);
                target.setQuantity(BigDecimal.ZERO);
                target.setEntryPrice(BigDecimal.ZERO);
            }

            BigDecimal existingQuantity = target.getQuantity();
            BigDecimal mergedQuantity = existingQuantity.add(remaining);
            BigDecimal weightedEntryPrice = existingQuantity.multiply(target.getEntryPrice())
                    .add(remaining.multiply(fillPrice))
                    .divide(mergedQuantity, 18, RoundingMode.HALF_UP);

            target.setQuantity(mergedQuantity);
            target.setEntryPrice(weightedEntryPrice);
            PositionEntity saved = positionRepository.save(target);

            TradeEntity openTrade = persistTrade(
                    userId,
                    orderId,
                    symbol,
                    orderSide,
                    remaining,
                    fillPrice,
                    BigDecimal.ZERO,
                    false,
                    liquidityRole,
                    feeRate
            );
            ledgerService.settleTrade(userId, openTrade.getId(), openTrade.getRealizedPnl(), openTrade.getFee());
            if (firstTrade == null) {
                firstTrade = openTrade;
            }
            return new ExecutionResult(firstTrade, saved, realizedPnlTotal);
        }

        PositionEntity updatedOpposite = Objects.requireNonNull(oppositePosition);
        return new ExecutionResult(firstTrade, updatedOpposite, realizedPnlTotal);
    }

    @Transactional
    public ClosePositionResponse closeMarket(String symbol, ClosePositionMarketRequest request) {
        List<PositionEntity> openPositions = positionRepository.findByUserIdAndSymbolAndQuantityGreaterThan(
                request.userId(), symbol, BigDecimal.ZERO);
        if (openPositions.isEmpty()) {
            throw new BadRequestException("no open position to close");
        }
        if (openPositions.size() > 1) {
            throw new BadRequestException("multiple open sides found, hedge mode close is not supported in V1");
        }

        PositionEntity position = openPositions.get(0);
        BigDecimal closeQuantity = request.quantity() == null ? position.getQuantity() : request.quantity();
        if (closeQuantity.compareTo(position.getQuantity()) > 0) {
            throw new BadRequestException("close quantity exceeds current position");
        }

        BigDecimal markPrice = request.markPrice() != null
                ? request.markPrice()
                : markPriceService.getMarkPrice(symbol).markPrice();
        String closeSide = position.getSide() == PositionSide.LONG ? "SELL" : "BUY";
        ExecutionResult execution = applyFill(
                request.userId(),
                null,
                symbol,
                closeSide,
                closeQuantity,
                markPrice,
                true,
                LiquidityRole.TAKER,
                new BigDecimal("0.0005")
        );

        return new ClosePositionResponse(
                execution.trade().getId(),
                execution.positionAfter().getId(),
                symbol,
                execution.positionAfter().getSide().name(),
                closeQuantity,
                markPrice,
                execution.positionAfter().getQuantity(),
                execution.realizedPnlTotal()
        );
    }

    private Optional<PositionEntity> findPosition(Long userId, String symbol, PositionSide side) {
        return positionRepository.findByUserIdAndSymbolAndSide(userId, symbol, side);
    }

    private boolean hasOpenPosition(PositionEntity position) {
        return position != null && position.getQuantity().compareTo(BigDecimal.ZERO) > 0;
    }

    private TradeEntity persistTrade(Long userId,
                                     Long orderId,
                                     String symbol,
                                     String side,
                                     BigDecimal quantity,
                                     BigDecimal price,
                                     BigDecimal realizedPnl,
                                     boolean closeTrade,
                                     LiquidityRole liquidityRole,
                                     BigDecimal feeRate) {
        TradeEntity trade = new TradeEntity();
        trade.setUserId(userId);
        trade.setOrderId(orderId);
        trade.setSymbol(symbol);
        trade.setSide(side);
        trade.setQuantity(quantity);
        trade.setPrice(price);
        trade.setLiquidityRole(liquidityRole);
        trade.setFee(quantity.multiply(price).multiply(feeRate));
        trade.setRealizedPnl(realizedPnl);
        trade.setCloseTrade(closeTrade);
        return tradeRepository.save(trade);
    }

    private PositionSide toPositionSide(String orderSide) {
        if ("BUY".equalsIgnoreCase(orderSide)) {
            return PositionSide.LONG;
        }
        if ("SELL".equalsIgnoreCase(orderSide)) {
            return PositionSide.SHORT;
        }
        throw new BadRequestException("side must be BUY or SELL");
    }

    private BigDecimal calculateRealizedPnl(PositionEntity position, BigDecimal quantity, BigDecimal fillPrice) {
        if (position.getSide() == PositionSide.LONG) {
            return fillPrice.subtract(position.getEntryPrice()).multiply(quantity);
        }
        return position.getEntryPrice().subtract(fillPrice).multiply(quantity);
    }

    public record ExecutionResult(TradeEntity trade, PositionEntity positionAfter, BigDecimal realizedPnlTotal) {
    }
}
