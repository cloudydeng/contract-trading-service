package com.matching.contract.engine;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.matching.contract.entity.ExecutionLogEntity;
import com.matching.contract.entity.LiquidityRole;
import com.matching.contract.entity.OrderEntity;
import com.matching.contract.entity.OrderStatus;
import com.matching.contract.entity.OrderType;
import com.matching.contract.entity.TimeInForce;
import com.matching.contract.persistence.AsyncPersistenceService;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.repository.ExecutionLogRepository;
import com.matching.contract.repository.OrderRepository;
import com.matching.contract.service.PositionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MatchingEngine {

    private static final BigDecimal MAKER_FEE_RATE = new BigDecimal("0.0002");
    private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.0005");

    private final PositionService positionService;
    private final OrderRepository orderRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final AsyncPersistenceService persistenceService;
    private final MarkPriceService markPriceService;
    private final int ringBufferSize;
    private final int maxSymbolWorkers;
    private final TransactionTemplate transactionTemplate;

    private final Map<String, SymbolWorker> workers = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public MatchingEngine(PositionService positionService,
                          OrderRepository orderRepository,
                          ExecutionLogRepository executionLogRepository,
                          AsyncPersistenceService persistenceService,
                          MarkPriceService markPriceService,
                          PlatformTransactionManager transactionManager,
                          @Value("${matching.ring-buffer-size:65536}") int ringBufferSize,
                          @Value("${matching.max-symbol-workers:256}") int maxSymbolWorkers) {
        this.positionService = positionService;
        this.orderRepository = orderRepository;
        this.executionLogRepository = executionLogRepository;
        this.persistenceService = persistenceService;
        this.markPriceService = markPriceService;
        this.ringBufferSize = toPowerOfTwo(Math.max(1024, ringBufferSize));
        this.maxSymbolWorkers = Math.max(1, maxSymbolWorkers);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void start() {
        List<ExecutionLogEntity> logs = executionLogRepository.findAllByOrderBySymbolAscSequenceIdAsc();
        for (ExecutionLogEntity log : logs) {
            getOrCreateWorker(log.getSymbol()).bootstrap(log);
        }

        List<OrderEntity> openOrders = orderRepository.findByStatusIn(EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED));
        for (OrderEntity order : openOrders) {
            getOrCreateWorker(order.getSymbol()).bootstrapOrder(order);
        }

        started.set(true);
        workers.values().forEach(SymbolWorker::start);
    }

    @PreDestroy
    void stop() {
        started.set(false);
        workers.values().forEach(SymbolWorker::stop);
    }

    public OrderEntity submit(OrderEntity order) {
        CompletableFuture<OrderEntity> result = new CompletableFuture<>();
        SymbolWorker worker = getOrCreateWorker(order.getSymbol());
        worker.startIfNeeded(started.get());
        worker.submit(Task.place(order, result));
        return await(result);
    }

    public OrderEntity cancel(OrderEntity order) {
        CompletableFuture<OrderEntity> result = new CompletableFuture<>();
        SymbolWorker worker = getOrCreateWorker(order.getSymbol());
        worker.startIfNeeded(started.get());
        worker.submit(Task.cancel(order, result));
        return await(result);
    }

    public void reset() {
        for (SymbolWorker worker : workers.values()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            worker.submit(Task.reset(future));
            await(future);
        }
    }

    private synchronized SymbolWorker getOrCreateWorker(String symbol) {
        SymbolWorker existing = workers.get(symbol);
        if (existing != null) {
            return existing;
        }
        if (workers.size() >= maxSymbolWorkers) {
            throw new IllegalStateException("too many active symbol workers: " + workers.size());
        }
        SymbolWorker created = new SymbolWorker(symbol);
        workers.put(symbol, created);
        return created;
    }

    private int toPowerOfTwo(int value) {
        int n = 1;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("matching worker timeout or failure", ex);
        }
    }

    private final class SymbolWorker {

        private final String symbol;
        private final OrderBook orderBook = new OrderBook();
        private final Map<Long, RestingOrder> restingOrderIndex = new ConcurrentHashMap<>();

        private volatile long lastSequence = -1L;
        private volatile boolean startedLocal = false;

        private Disruptor<SymbolEvent> disruptor;
        private RingBuffer<SymbolEvent> ringBuffer;

        private SymbolWorker(String symbol) {
            this.symbol = symbol;
        }

        synchronized void start() {
            if (startedLocal) {
                return;
            }
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "matching-symbol-" + symbol);
                thread.setDaemon(true);
                return thread;
            };
            disruptor = new Disruptor<>(
                    SymbolEvent::new,
                    ringBufferSize,
                    threadFactory,
                    ProducerType.MULTI,
                    new BlockingWaitStrategy()
            );
            EventHandler<SymbolEvent> handler = (event, sequence, endOfBatch) -> {
                Task task = event.task;
                if (task != null) {
                    process(task);
                    event.task = null;
                }
            };
            disruptor.handleEventsWith(handler);
            ringBuffer = disruptor.start();
            startedLocal = true;
        }

        synchronized void startIfNeeded(boolean globalStarted) {
            if (globalStarted) {
                start();
            }
        }

        synchronized void stop() {
            if (!startedLocal || disruptor == null) {
                return;
            }
            try {
                disruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                disruptor.halt();
            }
            startedLocal = false;
        }

        void submit(Task task) {
            if (!startedLocal || ringBuffer == null) {
                throw new IllegalStateException("symbol worker not started: " + symbol);
            }
            ringBuffer.publishEvent((event, sequence, payload) -> event.task = payload, task);
        }

        void bootstrap(ExecutionLogEntity log) {
            applyReplayLog(log);
        }

        void bootstrapOrder(OrderEntity order) {
            if (restingOrderIndex.containsKey(order.getId())) {
                return;
            }
            BigDecimal remaining = order.getQuantity().subtract(order.getFilledQuantity());
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            if (order.getOrderType() != OrderType.LIMIT || order.getTimeInForce() != TimeInForce.GTC) {
                return;
            }
            RestingOrder restingOrder = RestingOrder.from(order, remaining);
            orderBook.add(restingOrder);
            restingOrderIndex.put(order.getId(), restingOrder);
            if (lastSequence < 0) {
                lastSequence = persistenceService.findLastSequence(symbol);
            }
        }

        private void process(Task task) {
            SymbolState snapshot = snapshotState();
            try {
                switch (task.type) {
                    case PLACE -> {
                        TaskOutcome outcome = transactionTemplate.execute(status -> place(task.order));
                        applyOutcome(outcome);
                        task.orderResult.complete(outcome.savedOrder);
                    }
                    case CANCEL -> {
                        TaskOutcome outcome = transactionTemplate.execute(status -> cancelInternal(task.order));
                        applyOutcome(outcome);
                        task.orderResult.complete(outcome.savedOrder);
                    }
                    case RESET -> {
                        orderBook.clear();
                        restingOrderIndex.clear();
                        lastSequence = -1L;
                        task.voidResult.complete(null);
                    }
                }
            } catch (Exception ex) {
                if (task.orderResult != null) {
                    task.orderResult.completeExceptionally(ex);
                }
                if (task.voidResult != null) {
                    task.voidResult.completeExceptionally(ex);
                }
                restoreState(snapshot);
            }
        }

        private TaskOutcome place(OrderEntity order) {
            EventCollector events = new EventCollector(symbol, ensureSequenceInitialized());
            BigDecimal remaining = order.getQuantity().subtract(order.getFilledQuantity());
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                return TaskOutcome.of(order, events);
            }

            events.record("ORDER_RECEIVED", payload(order), "order.received", payload(order));

            if (order.getTimeInForce() == TimeInForce.FOK && !canFullyFill(order, remaining)) {
                order.setStatus(OrderStatus.CANCELED);
                OrderEntity saved = orderRepository.save(order);
                events.record("ORDER_CANCELED", payload(saved), "order.canceled", payload(saved));
                return TaskOutcome.of(saved, events);
            }

            while (remaining.compareTo(BigDecimal.ZERO) > 0) {
                RestingOrder maker = orderBook.bestOpposite(order.getSide());
                if (maker == null || !isMatchable(order, maker.price())) {
                    break;
                }

                BigDecimal fillQty = remaining.min(maker.remainingQuantity());
                BigDecimal fillPrice = maker.price();

                positionService.applyFill(
                        order.getUserId(),
                        order.getId(),
                        order.getSymbol(),
                        order.getSide(),
                        fillQty,
                        fillPrice,
                        Boolean.TRUE.equals(order.getReduceOnly()),
                        LiquidityRole.TAKER,
                        TAKER_FEE_RATE
                );
                positionService.applyFill(
                        maker.userId(),
                        maker.orderId(),
                        maker.symbol(),
                        maker.side(),
                        fillQty,
                        fillPrice,
                        Boolean.TRUE.equals(maker.reduceOnly()),
                        LiquidityRole.MAKER,
                        MAKER_FEE_RATE
                );

                String tradePayload = "takerOrderId=" + order.getId() + ",makerOrderId=" + maker.orderId()
                        + ",qty=" + fillQty + ",price=" + fillPrice;
                events.record("TRADE_EXECUTED", tradePayload, "trade.executed", tradePayload);
                events.tradePrice(fillPrice);

                remaining = remaining.subtract(fillQty);
                order.setFilledQuantity(order.getFilledQuantity().add(fillQty));

                OrderEntity makerOrder = orderRepository.findById(maker.orderId()).orElse(null);
                if (makerOrder == null) {
                    orderBook.removeHead(maker);
                    restingOrderIndex.remove(maker.orderId());
                    continue;
                }
                makerOrder.setFilledQuantity(makerOrder.getFilledQuantity().add(fillQty));
                makerOrder.setStatus(resolveStatus(makerOrder));
                makerOrder = orderRepository.save(makerOrder);
                events.record("ORDER_UPDATED", payload(makerOrder), "order.updated", payload(makerOrder));

                maker.consume(fillQty);
                if (maker.remainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
                    orderBook.removeHead(maker);
                    restingOrderIndex.remove(maker.orderId());
                }
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                if (order.getOrderType() == OrderType.LIMIT && order.getTimeInForce() == TimeInForce.GTC) {
                    order.setStatus(resolveStatus(order));
                    RestingOrder restingOrder = RestingOrder.from(order, remaining);
                    orderBook.add(restingOrder);
                    restingOrderIndex.put(order.getId(), restingOrder);
                } else {
                    order.setStatus(OrderStatus.CANCELED);
                }
            } else {
                order.setStatus(OrderStatus.FILLED);
                restingOrderIndex.remove(order.getId());
            }

            OrderEntity saved = orderRepository.save(order);
            events.record("ORDER_UPDATED", payload(saved), "order.updated", payload(saved));
            return TaskOutcome.of(saved, events);
        }

        private TaskOutcome cancelInternal(OrderEntity order) {
            EventCollector events = new EventCollector(symbol, ensureSequenceInitialized());
            OrderEntity current = orderRepository.findById(order.getId()).orElse(order);
            RestingOrder restingOrder = restingOrderIndex.remove(current.getId());
            if (restingOrder == null) {
                return TaskOutcome.of(current, events);
            }
            orderBook.remove(restingOrder);
            current.setStatus(OrderStatus.CANCELED);
            OrderEntity saved = orderRepository.save(current);
            events.record("ORDER_CANCELED", payload(saved), "order.canceled", payload(saved));
            return TaskOutcome.of(saved, events);
        }

        private void applyReplayLog(ExecutionLogEntity log) {
            if (!symbol.equals(log.getSymbol())) {
                return;
            }

            long last = Math.max(0L, lastSequence);
            if (log.getSequenceId() <= last) {
                return;
            }
            if (last > 0 && log.getSequenceId() != last + 1) {
                throw new IllegalStateException("execution_log sequence gap for symbol=" + symbol
                        + ", expected=" + (last + 1) + ", actual=" + log.getSequenceId());
            }
            lastSequence = log.getSequenceId();

            switch (log.getEventType()) {
                case "ORDER_UPDATED" -> applyOrderSnapshot(log.getPayload());
                case "ORDER_CANCELED" -> removeOrderFromBook(parseLong(log.getPayload(), "orderId"));
                default -> {
                }
            }
        }

        private void applyOrderSnapshot(String payload) {
            Long orderId = parseLong(payload, "orderId");
            removeOrderFromBook(orderId);

            OrderSnapshot snapshot = OrderSnapshot.fromPayload(payload);
            if (!snapshot.resting()) {
                return;
            }

            RestingOrder restingOrder = snapshot.toRestingOrder(orderId);
            orderBook.add(restingOrder);
            restingOrderIndex.put(orderId, restingOrder);
        }

        private void removeOrderFromBook(Long orderId) {
            if (orderId == null) {
                return;
            }
            RestingOrder existing = restingOrderIndex.remove(orderId);
            if (existing != null) {
                orderBook.remove(existing);
            }
        }

        private boolean canFullyFill(OrderEntity order, BigDecimal remaining) {
            BigDecimal executable = BigDecimal.ZERO;
            for (RestingOrder resting : orderBook.iterateOpposite(order.getSide())) {
                if (!isMatchable(order, resting.price())) {
                    break;
                }
                executable = executable.add(resting.remainingQuantity());
                if (executable.compareTo(remaining) >= 0) {
                    return true;
                }
            }
            return false;
        }

        private boolean isMatchable(OrderEntity taker, BigDecimal makerPrice) {
            if (taker.getOrderType() == OrderType.MARKET) {
                return true;
            }
            if ("BUY".equalsIgnoreCase(taker.getSide())) {
                return taker.getPrice().compareTo(makerPrice) >= 0;
            }
            return taker.getPrice().compareTo(makerPrice) <= 0;
        }

        private OrderStatus resolveStatus(OrderEntity order) {
            if (order.getFilledQuantity().compareTo(BigDecimal.ZERO) == 0) {
                return OrderStatus.NEW;
            }
            if (order.getFilledQuantity().compareTo(order.getQuantity()) >= 0) {
                return OrderStatus.FILLED;
            }
            return OrderStatus.PARTIALLY_FILLED;
        }

        private long ensureSequenceInitialized() {
            if (lastSequence < 0) {
                lastSequence = persistenceService.findLastSequence(symbol);
            }
            return lastSequence;
        }

        private void applyOutcome(TaskOutcome outcome) {
            for (PendingEvent event : outcome.events.events) {
                persistenceService.appendExecution(symbol, event.sequence, event.eventId, event.eventType, event.executionPayload);
                persistenceService.appendOutbox(event.eventId, event.outboxTopic, event.outboxPayload);
                persistenceService.appendWal(symbol, event.eventType, event.executionPayload);
            }
            for (BigDecimal price : outcome.events.tradePrices) {
                markPriceService.updateLastTradePrice(symbol, price);
            }
            lastSequence = outcome.events.sequenceCursor;
        }

        private SymbolState snapshotState() {
            List<RestingOrder> snapshotOrders = orderBook.snapshotOrders();
            return new SymbolState(snapshotOrders, lastSequence);
        }

        private void restoreState(SymbolState snapshot) {
            orderBook.clear();
            restingOrderIndex.clear();
            for (RestingOrder order : snapshot.orders) {
                RestingOrder copy = order.copy();
                orderBook.add(copy);
                restingOrderIndex.put(copy.orderId(), copy);
            }
            lastSequence = snapshot.lastSequence;
        }
    }

    private String payload(OrderEntity order) {
        return "orderId=" + order.getId()
                + ",userId=" + order.getUserId()
                + ",symbol=" + order.getSymbol()
                + ",status=" + order.getStatus()
                + ",filled=" + order.getFilledQuantity()
                + ",qty=" + order.getQuantity()
                + ",side=" + order.getSide()
                + ",price=" + order.getPrice()
                + ",reduceOnly=" + order.getReduceOnly()
                + ",orderType=" + order.getOrderType()
                + ",timeInForce=" + order.getTimeInForce();
    }

    private static Long parseLong(String payload, String key) {
        String raw = parseString(payload, key);
        return raw == null ? null : Long.parseLong(raw);
    }

    private static String parseString(String payload, String key) {
        String[] parts = payload.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private static final class SymbolEvent {
        private Task task;
    }

    private static final class Task {
        private final TaskType type;
        private final OrderEntity order;
        private final CompletableFuture<OrderEntity> orderResult;
        private final CompletableFuture<Void> voidResult;

        private Task(TaskType type,
                     OrderEntity order,
                     CompletableFuture<OrderEntity> orderResult,
                     CompletableFuture<Void> voidResult) {
            this.type = type;
            this.order = order;
            this.orderResult = orderResult;
            this.voidResult = voidResult;
        }

        static Task place(OrderEntity order, CompletableFuture<OrderEntity> result) {
            return new Task(TaskType.PLACE, order, result, null);
        }

        static Task cancel(OrderEntity order, CompletableFuture<OrderEntity> result) {
            return new Task(TaskType.CANCEL, order, result, null);
        }

        static Task reset(CompletableFuture<Void> result) {
            return new Task(TaskType.RESET, null, null, result);
        }
    }

    private enum TaskType {
        PLACE,
        CANCEL,
        RESET
    }

    private static final class SymbolState {
        private final List<RestingOrder> orders;
        private final long lastSequence;

        private SymbolState(List<RestingOrder> orders, long lastSequence) {
            this.orders = orders;
            this.lastSequence = lastSequence;
        }
    }

    private static final class PendingEvent {
        private final long sequence;
        private final String eventId;
        private final String eventType;
        private final String executionPayload;
        private final String outboxTopic;
        private final String outboxPayload;

        private PendingEvent(long sequence,
                             String eventId,
                             String eventType,
                             String executionPayload,
                             String outboxTopic,
                             String outboxPayload) {
            this.sequence = sequence;
            this.eventId = eventId;
            this.eventType = eventType;
            this.executionPayload = executionPayload;
            this.outboxTopic = outboxTopic;
            this.outboxPayload = outboxPayload;
        }
    }

    private static final class EventCollector {
        private final String symbol;
        private long sequenceCursor;
        private final List<PendingEvent> events = new ArrayList<>();
        private final List<BigDecimal> tradePrices = new ArrayList<>();

        private EventCollector(String symbol, long sequenceCursor) {
            this.symbol = symbol;
            this.sequenceCursor = sequenceCursor;
        }

        void record(String eventType, String executionPayload, String outboxTopic, String outboxPayload) {
            long sequence = ++sequenceCursor;
            String eventId = symbol + "-" + sequence;
            events.add(new PendingEvent(sequence, eventId, eventType, executionPayload, outboxTopic, outboxPayload));
        }

        void tradePrice(BigDecimal price) {
            tradePrices.add(price);
        }
    }

    private static final class TaskOutcome {
        private final OrderEntity savedOrder;
        private final EventCollector events;

        private TaskOutcome(OrderEntity savedOrder, EventCollector events) {
            this.savedOrder = savedOrder;
            this.events = events;
        }

        private static TaskOutcome of(OrderEntity savedOrder, EventCollector events) {
            return new TaskOutcome(savedOrder, events);
        }
    }

    private static final class OrderBook {

        private final TreeMap<BigDecimal, ArrayDeque<RestingOrder>> bids = new TreeMap<>(java.util.Comparator.reverseOrder());
        private final TreeMap<BigDecimal, ArrayDeque<RestingOrder>> asks = new TreeMap<>();

        RestingOrder bestOpposite(String takerSide) {
            if ("BUY".equalsIgnoreCase(takerSide)) {
                return head(asks);
            }
            return head(bids);
        }

        Iterable<RestingOrder> iterateOpposite(String takerSide) {
            return "BUY".equalsIgnoreCase(takerSide) ? flatten(asks) : flatten(bids);
        }

        void add(RestingOrder order) {
            levelsForSide(order.side()).computeIfAbsent(order.price(), key -> new ArrayDeque<>()).addLast(order);
        }

        void remove(RestingOrder order) {
            TreeMap<BigDecimal, ArrayDeque<RestingOrder>> levels = levelsForSide(order.side());
            ArrayDeque<RestingOrder> queue = levels.get(order.price());
            if (queue == null) {
                return;
            }
            queue.remove(order);
            if (queue.isEmpty()) {
                levels.remove(order.price());
            }
        }

        void removeHead(RestingOrder order) {
            TreeMap<BigDecimal, ArrayDeque<RestingOrder>> levels = levelsForSide(order.side());
            ArrayDeque<RestingOrder> queue = levels.get(order.price());
            if (queue == null) {
                return;
            }
            RestingOrder head = queue.pollFirst();
            if (!Objects.equals(head, order)) {
                throw new IllegalStateException("order book head mismatch");
            }
            if (queue.isEmpty()) {
                levels.remove(order.price());
            }
        }

        void clear() {
            bids.clear();
            asks.clear();
        }

        List<RestingOrder> snapshotOrders() {
            List<RestingOrder> snapshot = new ArrayList<>();
            for (ArrayDeque<RestingOrder> queue : bids.values()) {
                for (RestingOrder order : queue) {
                    snapshot.add(order.copy());
                }
            }
            for (ArrayDeque<RestingOrder> queue : asks.values()) {
                for (RestingOrder order : queue) {
                    snapshot.add(order.copy());
                }
            }
            return snapshot;
        }

        private RestingOrder head(TreeMap<BigDecimal, ArrayDeque<RestingOrder>> levels) {
            Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> best = levels.firstEntry();
            if (best == null || best.getValue().isEmpty()) {
                return null;
            }
            return best.getValue().peekFirst();
        }

        private Iterable<RestingOrder> flatten(TreeMap<BigDecimal, ArrayDeque<RestingOrder>> levels) {
            ArrayDeque<RestingOrder> result = new ArrayDeque<>();
            for (ArrayDeque<RestingOrder> queue : levels.values()) {
                result.addAll(queue);
            }
            return result;
        }

        private TreeMap<BigDecimal, ArrayDeque<RestingOrder>> levelsForSide(String side) {
            return "BUY".equalsIgnoreCase(side) ? bids : asks;
        }
    }

    private static final class OrderSnapshot {
        private final Long userId;
        private final String symbol;
        private final String side;
        private final BigDecimal price;
        private final BigDecimal quantity;
        private final BigDecimal filled;
        private final Boolean reduceOnly;
        private final OrderStatus status;
        private final OrderType orderType;
        private final TimeInForce timeInForce;

        private OrderSnapshot(Long userId,
                              String symbol,
                              String side,
                              BigDecimal price,
                              BigDecimal quantity,
                              BigDecimal filled,
                              Boolean reduceOnly,
                              OrderStatus status,
                              OrderType orderType,
                              TimeInForce timeInForce) {
            this.userId = userId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.filled = filled;
            this.reduceOnly = reduceOnly;
            this.status = status;
            this.orderType = orderType;
            this.timeInForce = timeInForce;
        }

        static OrderSnapshot fromPayload(String payload) {
            return new OrderSnapshot(
                    parseLong(payload, "userId"),
                    parseString(payload, "symbol"),
                    parseString(payload, "side"),
                    new BigDecimal(parseString(payload, "price")),
                    new BigDecimal(parseString(payload, "qty")),
                    new BigDecimal(parseString(payload, "filled")),
                    Boolean.parseBoolean(parseString(payload, "reduceOnly")),
                    OrderStatus.valueOf(parseString(payload, "status")),
                    OrderType.valueOf(parseString(payload, "orderType")),
                    TimeInForce.valueOf(parseString(payload, "timeInForce"))
            );
        }

        boolean resting() {
            if (status != OrderStatus.NEW && status != OrderStatus.PARTIALLY_FILLED) {
                return false;
            }
            if (orderType != OrderType.LIMIT || timeInForce != TimeInForce.GTC) {
                return false;
            }
            return quantity.subtract(filled).compareTo(BigDecimal.ZERO) > 0;
        }

        RestingOrder toRestingOrder(Long orderId) {
            return new RestingOrder(orderId, userId, symbol, side, price, quantity.subtract(filled), reduceOnly);
        }
    }

    private static final class RestingOrder {

        private final Long orderId;
        private final Long userId;
        private final String symbol;
        private final String side;
        private final BigDecimal price;
        private BigDecimal remainingQuantity;
        private final Boolean reduceOnly;

        private RestingOrder(Long orderId,
                             Long userId,
                             String symbol,
                             String side,
                             BigDecimal price,
                             BigDecimal remainingQuantity,
                             Boolean reduceOnly) {
            this.orderId = orderId;
            this.userId = userId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.remainingQuantity = remainingQuantity;
            this.reduceOnly = reduceOnly;
        }

        static RestingOrder from(OrderEntity order, BigDecimal remaining) {
            return new RestingOrder(
                    order.getId(),
                    order.getUserId(),
                    order.getSymbol(),
                    order.getSide(),
                    order.getPrice(),
                    remaining,
                    order.getReduceOnly()
            );
        }

        Long orderId() {
            return orderId;
        }

        Long userId() {
            return userId;
        }

        String symbol() {
            return symbol;
        }

        String side() {
            return side;
        }

        BigDecimal price() {
            return price;
        }

        BigDecimal remainingQuantity() {
            return remainingQuantity;
        }

        Boolean reduceOnly() {
            return reduceOnly;
        }

        void consume(BigDecimal quantity) {
            this.remainingQuantity = this.remainingQuantity.subtract(quantity);
        }

        RestingOrder copy() {
            return new RestingOrder(orderId, userId, symbol, side, price, remainingQuantity, reduceOnly);
        }
    }
}
