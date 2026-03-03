package com.matching.contract.repository;

import com.matching.contract.entity.ExecutionLogEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLogEntity, Long> {

    Optional<ExecutionLogEntity> findTopBySymbolOrderBySequenceIdDesc(String symbol);

    boolean existsBySymbolAndSequenceId(String symbol, Long sequenceId);

    boolean existsByEventId(String eventId);

    List<ExecutionLogEntity> findAllByOrderBySymbolAscSequenceIdAsc();
}
