package com.matching.contract.repository;

import com.matching.contract.entity.SettlementPriceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementPriceRepository extends JpaRepository<SettlementPriceEntity, Long> {
    List<SettlementPriceEntity> findTop20BySymbolOrderBySettlementTimeDesc(String symbol);
}
