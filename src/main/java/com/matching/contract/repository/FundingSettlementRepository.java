package com.matching.contract.repository;

import com.matching.contract.entity.FundingSettlementEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundingSettlementRepository extends JpaRepository<FundingSettlementEntity, Long> {
    List<FundingSettlementEntity> findTop50BySymbolOrderByFundingTimeDesc(String symbol);

    boolean existsByUserIdAndSymbolAndFundingTime(Long userId, String symbol, Instant fundingTime);
}
