package com.matching.contract.repository;

import com.matching.contract.entity.OutboxEventEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {
    boolean existsByEventId(String eventId);

    List<OutboxEventEntity> findTop200ByStatusInAndRetryCountLessThanOrderByIdAsc(Collection<String> statuses, Integer retryCount);
}
