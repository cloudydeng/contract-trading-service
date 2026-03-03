package com.matching.contract.service;

import com.matching.contract.entity.AccountBalanceEntity;
import com.matching.contract.entity.LedgerEntryEntity;
import com.matching.contract.entity.OrderEntity;
import com.matching.contract.exception.BadRequestException;
import com.matching.contract.repository.AccountBalanceRepository;
import com.matching.contract.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private static final String ASSET = "USDT";
    private static final BigDecimal DEFAULT_AVAILABLE = new BigDecimal("1000000");
    private static final BigDecimal TEN = new BigDecimal("10");

    private final AccountBalanceRepository accountBalanceRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(AccountBalanceRepository accountBalanceRepository,
                         LedgerEntryRepository ledgerEntryRepository) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public void reserveMargin(OrderEntity order) {
        AccountBalanceEntity balance = loadOrInit(order.getUserId());
        BigDecimal margin = estimateMargin(order);
        if (balance.getAvailable().compareTo(margin) < 0) {
            throw new BadRequestException("insufficient available balance");
        }
        balance.setAvailable(balance.getAvailable().subtract(margin));
        balance.setFrozen(balance.getFrozen().add(margin));
        balance.setUpdatedAt(Instant.now());
        accountBalanceRepository.save(balance);

        appendLedger(order.getUserId(), "MARGIN_RESERVE", "order-" + order.getId(), margin.negate(), balance.getAvailable());
    }

    @Transactional
    public void releaseMargin(OrderEntity order) {
        AccountBalanceEntity balance = loadOrInit(order.getUserId());
        BigDecimal margin = estimateMargin(order);
        BigDecimal release = margin.min(balance.getFrozen());
        balance.setFrozen(balance.getFrozen().subtract(release));
        balance.setAvailable(balance.getAvailable().add(release));
        balance.setUpdatedAt(Instant.now());
        accountBalanceRepository.save(balance);

        appendLedger(order.getUserId(), "MARGIN_RELEASE", "order-" + order.getId(), release, balance.getAvailable());
    }

    @Transactional
    public void settleTrade(Long userId, Long tradeId, BigDecimal realizedPnl, BigDecimal fee) {
        AccountBalanceEntity balance = loadOrInit(userId);
        String refId = "trade-" + tradeId;

        if (realizedPnl.compareTo(BigDecimal.ZERO) != 0
                && !ledgerEntryRepository.existsByUserIdAndBizTypeAndRefIdAndAsset(userId, "REALIZED_PNL", refId, ASSET)) {
            balance.setAvailable(balance.getAvailable().add(realizedPnl));
            appendLedger(userId, "REALIZED_PNL", refId, realizedPnl, balance.getAvailable());
        }

        if (fee.compareTo(BigDecimal.ZERO) > 0
                && !ledgerEntryRepository.existsByUserIdAndBizTypeAndRefIdAndAsset(userId, "TRADE_FEE", refId, ASSET)) {
            balance.setAvailable(balance.getAvailable().subtract(fee));
            appendLedger(userId, "TRADE_FEE", refId, fee.negate(), balance.getAvailable());
        }

        balance.setUpdatedAt(Instant.now());
        accountBalanceRepository.save(balance);
    }

    @Transactional
    public void settleFunding(Long userId, Long settlementId, BigDecimal amount) {
        AccountBalanceEntity balance = loadOrInit(userId);
        String refId = "funding-" + settlementId;
        if (ledgerEntryRepository.existsByUserIdAndBizTypeAndRefIdAndAsset(userId, "FUNDING", refId, ASSET)) {
            return;
        }
        balance.setAvailable(balance.getAvailable().add(amount));
        balance.setUpdatedAt(Instant.now());
        accountBalanceRepository.save(balance);
        appendLedger(userId, "FUNDING", refId, amount, balance.getAvailable());
    }

    private BigDecimal estimateMargin(OrderEntity order) {
        return order.getQuantity().multiply(order.getPrice()).divide(TEN, 18, BigDecimal.ROUND_HALF_UP);
    }

    private AccountBalanceEntity loadOrInit(Long userId) {
        return accountBalanceRepository.findByUserIdAndAsset(userId, ASSET)
                .orElseGet(() -> {
                    AccountBalanceEntity entity = new AccountBalanceEntity();
                    entity.setUserId(userId);
                    entity.setAsset(ASSET);
                    entity.setAvailable(DEFAULT_AVAILABLE);
                    entity.setFrozen(BigDecimal.ZERO);
                    entity.setUpdatedAt(Instant.now());
                    return accountBalanceRepository.save(entity);
                });
    }

    private void appendLedger(Long userId, String bizType, String refId, BigDecimal delta, BigDecimal balanceAfter) {
        if (ledgerEntryRepository.existsByUserIdAndBizTypeAndRefIdAndAsset(userId, bizType, refId, ASSET)) {
            return;
        }
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setUserId(userId);
        entry.setAsset(ASSET);
        entry.setBizType(bizType);
        entry.setRefId(refId);
        entry.setDelta(delta);
        entry.setBalanceAfter(balanceAfter);
        ledgerEntryRepository.save(entry);
    }
}
