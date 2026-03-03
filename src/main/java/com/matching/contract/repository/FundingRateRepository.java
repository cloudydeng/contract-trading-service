package com.matching.contract.repository;

import com.matching.contract.entity.FundingRateEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundingRateRepository extends JpaRepository<FundingRateEntity, Long> {
    Optional<FundingRateEntity> findTopBySymbolOrderByFundingTimeDesc(String symbol);

    List<FundingRateEntity> findTop20BySymbolOrderByFundingTimeDesc(String symbol);

    boolean existsBySymbolAndFundingTime(String symbol, Instant fundingTime);
}
