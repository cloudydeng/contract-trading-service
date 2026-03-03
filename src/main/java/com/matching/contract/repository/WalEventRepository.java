package com.matching.contract.repository;

import com.matching.contract.entity.WalEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalEventRepository extends JpaRepository<WalEventEntity, Long> {
}
