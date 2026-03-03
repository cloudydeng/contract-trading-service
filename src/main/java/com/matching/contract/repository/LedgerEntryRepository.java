package com.matching.contract.repository;

import com.matching.contract.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {
    boolean existsByUserIdAndBizTypeAndRefIdAndAsset(Long userId, String bizType, String refId, String asset);
}
