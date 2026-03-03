package com.matching.contract.repository;

import com.matching.contract.entity.PositionEntity;
import com.matching.contract.entity.PositionSide;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    Optional<PositionEntity> findByUserIdAndSymbolAndSide(Long userId, String symbol, PositionSide side);

    List<PositionEntity> findByUserIdAndSymbolAndQuantityGreaterThan(Long userId, String symbol, BigDecimal quantity);

    List<PositionEntity> findByQuantityGreaterThan(BigDecimal quantity);
}
