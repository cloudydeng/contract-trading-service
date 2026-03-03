package com.matching.contract.persistence;

import com.matching.contract.entity.OutboxEventEntity;
import com.matching.contract.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);
    private static final int MAX_RETRY = 5;

    private final OutboxEventRepository outboxEventRepository;

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(fixedDelayString = "${outbox.publish-interval-ms:1000}")
    @Transactional
    public void publishBatch() {
        List<OutboxEventEntity> batch = outboxEventRepository
                .findTop200ByStatusInAndRetryCountLessThanOrderByIdAsc(Set.of("NEW", "FAILED"), MAX_RETRY + 1);

        for (OutboxEventEntity event : batch) {
            try {
                publish(event.getTopic(), event.getPayload());
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception ex) {
                int retry = event.getRetryCount() + 1;
                event.setRetryCount(retry);
                event.setLastError(ex.getMessage());
                event.setStatus(retry > MAX_RETRY ? "DLQ" : "FAILED");
            }
            outboxEventRepository.save(event);
        }
    }

    private void publish(String topic, String payload) {
        // Placeholder for Kafka/RocketMQ/etc.
        if (topic != null && topic.contains("simulate.fail")) {
            throw new IllegalStateException("simulated publish failure");
        }
        log.debug("publish topic={} payload={}", topic, payload);
    }
}
