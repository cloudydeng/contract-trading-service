package com.matching.contract.repository;

import com.matching.contract.entity.TradeEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
    List<TradeEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<TradeEntity> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    List<TradeEntity> findByUserIdAndOrderIdOrderByCreatedAtDesc(Long userId, Long orderId);
}
