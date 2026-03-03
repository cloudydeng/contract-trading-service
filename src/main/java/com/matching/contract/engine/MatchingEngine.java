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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MatchingEngine {

    private static final BigDecimal MAKER_FEE_RATE = new BigDecimal("0.0002");
    private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.0005");

    private final PositionService positionService;
    private final OrderRepository orderRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final AsyncPersistenceService persistenceService;
    private final MarkPriceService markPriceService;
    private final int shardCount;
    private final int ringBufferSize;

    private final List<ShardWorker> workers = new ArrayList<>();

    public MatchingEngine(PositionService positionService,
                          OrderRepository orderRepository,
                          ExecutionLogRepository executionLogRepository,
                          AsyncPersistenceService persistenceService,
                          MarkPriceService markPriceService,
                          @Value("${matching.shards:4}") int shardCount,
                          @Value("${matching.ring-buffer-size:65536}") int ringBufferSize) {
        this.positionService = positionService;
        this.orderRepository = orderRepository;
        this.executionLogRepository = executionLogRepository;
        this.persistenceService = persistenceService;
        this.markPriceService = markPriceService;
        this.shardCount = Math.max(1, shardCount);
        this.ringBufferSize = toPowerOfTwo(Math.max(1024, ringBufferSize));
    }

    @PostConstruct
    void start() {
        for (int i = 0; i < shardCount; i++) {
            workers.add(new ShardWorker(i));
        }

        // Full replay: rebuild in-memory books from execution_log ordered by symbol+seq.
        List<ExecutionLogEntity> logs = executionLogRepository.findAllByOrderBySymbolAscSequenceIdAsc();
        for (ExecutionLogEntity log : logs) {
            route(log.getSymbol()).bootstrap(log);
        }

        // Fallback for old data: replay open orders that are not in memory yet.
        List<OrderEntity> openOrders = orderRepository.findByStatusIn(EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED));
        for (OrderEntity order : openOrders) {
            route(order.getSymbol()).bootstrapOrder(order);
        }

        for (ShardWorker worker : workers) {
            worker.start();
        }
    }

    @PreDestroy
    void stop() {
        for (ShardWorker worker : workers) {
            worker.stop();
        }
    }

    public OrderEntity submit(OrderEntity order) {
        CompletableFuture<OrderEntity> result = new CompletableFuture<>();
        route(order.getSymbol()).submit(Task.place(order, result));
        return await(result);
    }

    public void cancel(OrderEntity order) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        route(order.getSymbol()).submit(Task.cancel(order, result));
        await(result);
    }

    public void reset() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ShardWorker worker : workers) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);
            worker.submit(Task.reset(future));
        }
        for (CompletableFuture<Void> future : futures) {
            await(future);
        }
    }

    private ShardWorker route(String symbol) {
        int idx = Math.floorMod(symbol.hashCode(), shardCount);
        return workers.get(idx);
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

    private final class ShardWorker {

        private final int shardId;
        private final Map<String, OrderBook> books = new HashMap<>();
        private final Map<Long, RestingOrder> restingOrderIndex = new HashMap<>();
        private final Map<String, Long> lastSequenceBySymbol = new HashMap<>();
        private Disruptor<ShardEvent> disruptor;
        private RingBuffer<ShardEvent> ringBuffer;

        private ShardWorker(int shardId) {
            this.shardId = shardId;
        }

        void start() {
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "matching-shard-" + shardId);
                thread.setDaemon(true);
                return thread;
            };
            disruptor = new Disruptor<>(
                    ShardEvent::new,
                    ringBufferSize,
                    threadFactory,
                    ProducerType.MULTI,
                    new BlockingWaitStrategy()
            );
            EventHandler<ShardEvent> handler = (event, sequence, endOfBatch) -> {
                Task task = event.task;
                if (task != null) {
                    process(task);
                    event.task = null;
                }
            };
            disruptor.handleEventsWith(handler);
            ringBuffer = disruptor.start();
        }

        void stop() {
            if (disruptor == null) {
                return;
            }
            try {
                disruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                disruptor.halt();
            }
        }

        void submit(Task task) {
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
            OrderBook orderBook = books.computeIfAbsent(order.getSymbol(), key -> new OrderBook());
            RestingOrder restingOrder = RestingOrder.from(order, remaining);
            orderBook.add(restingOrder);
            restingOrderIndex.put(order.getId(), restingOrder);
            lastSequenceBySymbol.computeIfAbsent(order.getSymbol(), persistenceService::findLastSequence);
        }

        private void process(Task task) {
            try {
                switch (task.type) {
                    case PLACE -> task.placeResult.complete(place(task.order));
                    case CANCEL -> {
                        cancelInternal(task.order);
                        task.voidResult.complete(null);
                    }
                    case RESET -> {
                        books.clear();
                        restingOrderIndex.clear();
                        lastSequenceBySymbol.clear();
                        task.voidResult.complete(null);
                    }
                }
            } catch (Exception ex) {
                if (task.placeResult != null) {
                    task.placeResult.completeExceptionally(ex);
                }
                if (task.voidResult != null) {
                    task.voidResult.completeExceptionally(ex);
                }
            }
        }

        private OrderEntity place(OrderEntity order) {
            ensureSequenceInitialized(order.getSymbol());
            OrderBook orderBook = books.computeIfAbsent(order.getSymbol(), key -> new OrderBook());
            BigDecimal remaining = order.getQuantity().subtract(order.getFilledQuantity());
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                return order;
            }

            recordEvent(order.getSymbol(), "ORDER_RECEIVED", payload(order), "order.received", payload(order));

            if (order.getTimeInForce() == TimeInForce.FOK && !canFullyFill(orderBook, order, remaining)) {
                order.setStatus(OrderStatus.CANCELED);
                OrderEntity saved = orderRepository.save(order);
                recordEvent(order.getSymbol(), "ORDER_CANCELED", payload(saved), "order.canceled", payload(saved));
                return saved;
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
                recordEvent(order.getSymbol(), "TRADE_EXECUTED", tradePayload, "trade.executed", tradePayload);
                markPriceService.updateLastTradePrice(order.getSymbol(), fillPrice);

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
                recordEvent(makerOrder.getSymbol(), "ORDER_UPDATED", payload(makerOrder), "order.updated", payload(makerOrder));

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
            recordEvent(order.getSymbol(), "ORDER_UPDATED", payload(saved), "order.updated", payload(saved));
            return saved;
        }

        private void cancelInternal(OrderEntity order) {
            RestingOrder restingOrder = restingOrderIndex.remove(order.getId());
            if (restingOrder == null) {
                return;
            }
            OrderBook book = books.get(restingOrder.symbol());
            if (book != null) {
                book.remove(restingOrder);
            }
            recordEvent(order.getSymbol(), "ORDER_CANCELED", payload(order), "order.canceled", payload(order));
        }

        private void applyReplayLog(ExecutionLogEntity log) {
            String symbol = log.getSymbol();
            long last = lastSequenceBySymbol.getOrDefault(symbol, 0L);
            if (log.getSequenceId() <= last) {
                return;
            }
            if (log.getSequenceId() != last + 1) {
                throw new IllegalStateException("execution_log sequence gap for symbol=" + symbol
                        + ", expected=" + (last + 1) + ", actual=" + log.getSequenceId());
            }
            lastSequenceBySymbol.put(symbol, log.getSequenceId());

            switch (log.getEventType()) {
                case "ORDER_UPDATED" -> applyOrderSnapshot(log.getPayload());
                case "ORDER_CANCELED" -> removeOrderFromBook(parseLong(log.getPayload(), "orderId"));
                default -> {
                    // ORDER_RECEIVED / TRADE_EXECUTED only move checkpoint.
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

            OrderBook orderBook = books.computeIfAbsent(snapshot.symbol, key -> new OrderBook());
            RestingOrder restingOrder = snapshot.toRestingOrder(orderId);
            orderBook.add(restingOrder);
            restingOrderIndex.put(orderId, restingOrder);
        }

        private void removeOrderFromBook(Long orderId) {
            if (orderId == null) {
                return;
            }
            RestingOrder existing = restingOrderIndex.remove(orderId);
            if (existing == null) {
                return;
            }
            OrderBook book = books.get(existing.symbol());
            if (book != null) {
                book.remove(existing);
            }
        }

        private boolean canFullyFill(OrderBook book, OrderEntity order, BigDecimal remaining) {
            BigDecimal executable = BigDecimal.ZERO;
            for (RestingOrder resting : book.iterateOpposite(order.getSide())) {
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

        private void ensureSequenceInitialized(String symbol) {
            lastSequenceBySymbol.computeIfAbsent(symbol, persistenceService::findLastSequence);
        }

        private void recordEvent(String symbol, String eventType, String executionPayload, String outboxTopic, String outboxPayload) {
            long sequence = lastSequenceBySymbol.compute(symbol, (key, old) -> old == null ? 1L : old + 1L);
            String eventId = symbol + "-" + sequence;
            persistenceService.appendExecution(symbol, sequence, eventId, eventType, executionPayload);
            persistenceService.appendOutbox(eventId, outboxTopic, outboxPayload);
            persistenceService.appendWal(symbol, eventType, executionPayload);
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

    private static final class ShardEvent {
        private Task task;
    }

    private static final class Task {
        private final TaskType type;
        private final OrderEntity order;
        private final CompletableFuture<OrderEntity> placeResult;
        private final CompletableFuture<Void> voidResult;

        private Task(TaskType type,
                     OrderEntity order,
                     CompletableFuture<OrderEntity> placeResult,
                     CompletableFuture<Void> voidResult) {
            this.type = type;
            this.order = order;
            this.placeResult = placeResult;
            this.voidResult = voidResult;
        }

        static Task place(OrderEntity order, CompletableFuture<OrderEntity> result) {
            return new Task(TaskType.PLACE, order, result, null);
        }

        static Task cancel(OrderEntity order, CompletableFuture<Void> result) {
            return new Task(TaskType.CANCEL, order, null, result);
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

    private static final class OrderBook {

        private final TreeMap<BigDecimal, Deque<RestingOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
        private final TreeMap<BigDecimal, Deque<RestingOrder>> asks = new TreeMap<>();

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
            levelsForSide(order.side())
                    .computeIfAbsent(order.price(), key -> new ArrayDeque<>())
                    .addLast(order);
        }

        void remove(RestingOrder order) {
            TreeMap<BigDecimal, Deque<RestingOrder>> levels = levelsForSide(order.side());
            Deque<RestingOrder> queue = levels.get(order.price());
            if (queue == null) {
                return;
            }
            queue.remove(order);
            if (queue.isEmpty()) {
                levels.remove(order.price());
            }
        }

        void removeHead(RestingOrder order) {
            TreeMap<BigDecimal, Deque<RestingOrder>> levels = levelsForSide(order.side());
            Deque<RestingOrder> queue = levels.get(order.price());
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

        private RestingOrder head(TreeMap<BigDecimal, Deque<RestingOrder>> levels) {
            Map.Entry<BigDecimal, Deque<RestingOrder>> best = levels.firstEntry();
            if (best == null || best.getValue().isEmpty()) {
                return null;
            }
            return best.getValue().peekFirst();
        }

        private Iterable<RestingOrder> flatten(TreeMap<BigDecimal, Deque<RestingOrder>> levels) {
            Deque<RestingOrder> result = new ArrayDeque<>();
            for (Deque<RestingOrder> queue : levels.values()) {
                result.addAll(queue);
            }
            return result;
        }

        private TreeMap<BigDecimal, Deque<RestingOrder>> levelsForSide(String side) {
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
    }
}
