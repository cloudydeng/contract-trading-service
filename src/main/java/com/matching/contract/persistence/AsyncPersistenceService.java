package com.matching.contract.persistence;

import com.matching.contract.entity.ExecutionLogEntity;
import com.matching.contract.entity.OutboxEventEntity;
import com.matching.contract.entity.WalEventEntity;
import com.matching.contract.repository.ExecutionLogRepository;
import com.matching.contract.repository.OutboxEventRepository;
import com.matching.contract.repository.WalEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class AsyncPersistenceService {

    private static final int BATCH_SIZE = 128;

    private final WalEventRepository walEventRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final OutboxEventRepository outboxEventRepository;

    private final LinkedBlockingQueue<PersistTask> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public AsyncPersistenceService(WalEventRepository walEventRepository,
                                   ExecutionLogRepository executionLogRepository,
                                   OutboxEventRepository outboxEventRepository) {
        this.walEventRepository = walEventRepository;
        this.executionLogRepository = executionLogRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostConstruct
    void start() {
        running.set(true);
        worker = new Thread(this::runLoop, "async-persistence-writer");
        worker.start();
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void appendWal(String symbol, String eventType, String payload) {
        queue.offer(PersistTask.wal(symbol, eventType, payload));
    }

    public void appendOutbox(String topic, String payload) {
        queue.offer(PersistTask.outbox(topic + ":" + payload.hashCode(), topic, payload));
    }

    public void appendOutbox(String eventId, String topic, String payload) {
        queue.offer(PersistTask.outbox(eventId, topic, payload));
    }

    public void appendExecution(String symbol, long sequenceId, String eventId, String eventType, String payload) {
        queue.offer(PersistTask.execution(symbol, sequenceId, eventId, eventType, payload));
    }

    public long findLastSequence(String symbol) {
        return executionLogRepository.findTopBySymbolOrderBySequenceIdDesc(symbol)
                .map(ExecutionLogEntity::getSequenceId)
                .orElse(0L);
    }

    private void runLoop() {
        List<PersistTask> batch = new ArrayList<>(BATCH_SIZE);
        while (running.get() || !queue.isEmpty()) {
            try {
                PersistTask first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                flush(batch);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                // Retry once by re-enqueueing; durability over latency in failure path.
                for (PersistTask task : batch) {
                    queue.offer(task);
                }
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<PersistTask> batch) {
        List<WalEventEntity> walEvents = new ArrayList<>();
        List<ExecutionLogEntity> executionLogs = new ArrayList<>();
        List<OutboxEventEntity> outboxEvents = new ArrayList<>();
        Instant now = Instant.now();

        for (PersistTask task : batch) {
            if (task.type == PersistType.WAL) {
                WalEventEntity wal = new WalEventEntity();
                wal.setSymbol(task.symbol);
                wal.setEventType(task.eventType);
                wal.setPayload(task.payload);
                wal.setCreatedAt(now);
                walEvents.add(wal);
            } else if (task.type == PersistType.EXECUTION) {
                if (executionLogRepository.existsByEventId(task.eventId)
                        || executionLogRepository.existsBySymbolAndSequenceId(task.symbol, task.sequenceId)) {
                    continue;
                }
                ExecutionLogEntity execution = new ExecutionLogEntity();
                execution.setSymbol(task.symbol);
                execution.setSequenceId(task.sequenceId);
                execution.setEventId(task.eventId);
                execution.setEventType(task.eventType);
                execution.setPayload(task.payload);
                execution.setApplied(Boolean.TRUE);
                execution.setCreatedAt(now);
                executionLogs.add(execution);
            } else {
                if (outboxEventRepository.existsByEventId(task.eventId)) {
                    continue;
                }
                OutboxEventEntity outbox = new OutboxEventEntity();
                outbox.setEventId(task.eventId);
                outbox.setTopic(task.topic);
                outbox.setPayload(task.payload);
                outbox.setStatus("NEW");
                outbox.setCreatedAt(now);
                outboxEvents.add(outbox);
            }
        }

        if (!walEvents.isEmpty()) {
            walEventRepository.saveAll(walEvents);
        }
        if (!executionLogs.isEmpty()) {
            executionLogRepository.saveAll(executionLogs);
        }
        if (!outboxEvents.isEmpty()) {
            outboxEventRepository.saveAll(outboxEvents);
        }
    }

    private enum PersistType {
        WAL,
        EXECUTION,
        OUTBOX
    }

    private static final class PersistTask {
        private final PersistType type;
        private final String symbol;
        private final Long sequenceId;
        private final String eventId;
        private final String eventType;
        private final String topic;
        private final String payload;

        private PersistTask(PersistType type,
                            String symbol,
                            Long sequenceId,
                            String eventId,
                            String eventType,
                            String topic,
                            String payload) {
            this.type = type;
            this.symbol = symbol;
            this.sequenceId = sequenceId;
            this.eventId = eventId;
            this.eventType = eventType;
            this.topic = topic;
            this.payload = payload;
        }

        static PersistTask wal(String symbol, String eventType, String payload) {
            return new PersistTask(PersistType.WAL, symbol, null, null, eventType, null, payload);
        }

        static PersistTask execution(String symbol, long sequenceId, String eventId, String eventType, String payload) {
            return new PersistTask(PersistType.EXECUTION, symbol, sequenceId, eventId, eventType, null, payload);
        }

        static PersistTask outbox(String eventId, String topic, String payload) {
            return new PersistTask(PersistType.OUTBOX, null, null, eventId, null, topic, payload);
        }
    }
}
