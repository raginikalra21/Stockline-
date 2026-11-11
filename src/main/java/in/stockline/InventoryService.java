package in.stockline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.function.LongConsumer;

import static in.stockline.Models.*;

@Service
class InventoryService {
    private static final Comparator<Stock> STOCK_ORDER = Comparator.comparing(Stock::sku)
        .thenComparing(Stock::warehouseCode);

    private final Store store;
    private final TransactionTemplate tx;
    private final Duration fulfillmentDelay;
    private final int retryMaxAttempts;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    record RetrySettings(int maxAttempts, Duration baseDelay, Duration maxDelay) {}

    @Autowired
    InventoryService(Store store, PlatformTransactionManager manager,
                     @Value("${stockline.fulfillment-delay:8s}") Duration fulfillmentDelay,
                     @Value("${stockline.retry.max-attempts:8}") int retryMaxAttempts,
                     @Value("${stockline.retry.base-delay:2ms}") Duration retryBaseDelay,
                     @Value("${stockline.retry.max-delay:100ms}") Duration retryMaxDelay) {
        this(store, manager, fulfillmentDelay, new RetrySettings(retryMaxAttempts, retryBaseDelay, retryMaxDelay));
    }

    InventoryService(Store store, PlatformTransactionManager manager, Duration fulfillmentDelay) {
        this(store, manager, fulfillmentDelay,
            new RetrySettings(8, Duration.ofMillis(2), Duration.ofMillis(100)));
    }

    InventoryService(Store store, PlatformTransactionManager manager, Duration fulfillmentDelay, RetrySettings retry) {
        if (retry.maxAttempts() < 1 || retry.maxAttempts() > 100) {
            throw new IllegalArgumentException("stockline.retry.max-attempts must be between 1 and 100");
        }
        if (retry.baseDelay().isNegative() || retry.baseDelay().isZero()
            || retry.maxDelay().compareTo(retry.baseDelay()) < 0
            || retry.maxDelay().compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Retry delays must be positive, ordered, and at most 10 seconds");
        }
        this.store = store;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.fulfillmentDelay = fulfillmentDelay;
        this.retryMaxAttempts = retry.maxAttempts();
        this.retryBaseDelay = retry.baseDelay();
        this.retryMaxDelay = retry.maxDelay();
    }

    private record Reservation(Stock snapshot, int quantity) {}
    private static class StaleStock extends RuntimeException {}
    private static class ExistingRequest extends RuntimeException {}
    private static class ReplayedOperation extends RuntimeException {}

    Placement place(OrderInput input) {
        return place(input, () -> {}, () -> {});
    }

    // Simulation-only overload: callers use it only with disposable H2 data, never live reservations.
    Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict) {
        return place(input, firstPlanReady, onVersionConflict, () -> {});
    }

    Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict, Runnable onRetry) {
        return place(input, firstPlanReady, onVersionConflict, onRetry, ignored -> {});
    }

    Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict, Runnable onRetry,
                     LongConsumer onTransactionNanos) {
        var lines = new TreeMap<String, Integer>();
        input.lines().forEach(line -> lines.merge(line.sku(), line.quantity(), Integer::sum));
        String customer = input.customer().strip();
        String preferredWarehouse = input.preferredWarehouseCode();
        String fingerprint = fingerprint(customer, lines, preferredWarehouse);
        UUID id = UUID.randomUUID();

        try {
            var firstAttempt = new java.util.concurrent.atomic.AtomicBoolean(true);
            return retry(() -> {
                var existing = store.byKey(input.requestKey());
                if (existing.isPresent()) {
                    return replay(existing.get(), fingerprint);
                }

                OffsetDateTime created = now();
                try {
                    store.insertOrder(id, input.requestKey(), fingerprint, customer, created);
                } catch (DuplicateKeyException duplicate) {
                    throw new ExistingRequest();
                }
                store.insertOperation(id, null, "ORDER_RESERVATION", fingerprint, created);

                List<Reservation> plan;
                try {
                    plan = plan(lines, preferredWarehouse);
                } catch (ApiProblem rejected) {
                    if (firstAttempt.getAndSet(false)) firstPlanReady.run();
                    throw rejected;
                }
                if (firstAttempt.getAndSet(false)) firstPlanReady.run();
                for (Reservation reservation : plan) {
                    Stock stock = reservation.snapshot();
                    int quantity = reservation.quantity();
                    change(stock, -quantity, quantity, 0, 0, 0, MovementType.RESERVATION, id,
                        null, null, "Customer order reservation", id);
                    store.insertAllocation(id, new Allocation(stock.sku(), stock.warehouseCode(), quantity));
                }
                store.enqueue(id, now().plus(fulfillmentDelay));
                return new Placement(order(id), false);
            }, onVersionConflict, onRetry, onTransactionNanos);
        } catch (ExistingRequest duplicate) {
            return replay(store.byKey(input.requestKey()).orElseThrow(), fingerprint);
        }
    }

    private Placement replay(Store.OrderHeader existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiProblem.conflict("IDEMPOTENCY_MISMATCH", "This request key was already used for a different order.");
        }
        return new Placement(existing.withAllocations(store.allocations(existing.id())), true);
    }

    private List<Reservation> plan(Map<String, Integer> lines, String preferredWarehouse) {
        if (preferredWarehouse != null && !store.warehouseExists(preferredWarehouse)) {
            throw ApiProblem.notFound("That warehouse does not exist.");
        }
        var plan = new ArrayList<Reservation>();
        for (var line : lines.entrySet()) {
            String sku = line.getKey();
            int remaining = line.getValue();
            List<Stock> candidates = store.stockForSku(sku);
            if (candidates.isEmpty()) {
                throw ApiProblem.notFound("Unknown SKU " + sku + ".");
            }
            if (preferredWarehouse != null) {
                // List.sort is stable: the preferred location is tried first, and all remaining
                // locations retain their configured warehouse priority.
                candidates.sort(Comparator.comparing((Stock stock) -> !stock.warehouseCode().equals(preferredWarehouse)));
            }
            for (Stock stock : candidates) {
                int quantity = (int) Math.min(remaining, stock.available());
                if (quantity > 0) {
                    plan.add(new Reservation(stock, quantity));
                    remaining -= quantity;
                }
                if (remaining == 0) {
                    break;
                }
            }
            if (remaining > 0) {
                throw ApiProblem.conflict("INSUFFICIENT_STOCK", "%s has %d available but %d requested."
                    .formatted(sku, line.getValue() - remaining, line.getValue()));
            }
        }
        // All writers acquire stock rows in this order, even when allocation priority differs.
        plan.sort(Comparator.comparing(Reservation::snapshot, STOCK_ORDER));
        return plan;
    }

    Product addProduct(ProductInput input) {
        Product product = new Product(input.sku(), input.name().strip(), input.category().strip());
        try {
            return retry(() -> {
                store.addProduct(product);
                return product;
            });
        } catch (DuplicateKeyException duplicate) {
            throw ApiProblem.conflict("DUPLICATE_SKU", "That SKU already exists.");
        }
    }

    Warehouse addWarehouse(WarehouseInput input) {
        Warehouse warehouse = new Warehouse(input.code(), input.name().strip(), input.city().strip(), input.priority());
        try {
            return retry(() -> {
                store.addWarehouse(warehouse);
                return warehouse;
            });
        } catch (DuplicateKeyException duplicate) {
            throw ApiProblem.conflict("DUPLICATE_WAREHOUSE", "That warehouse code already exists.");
        }
    }

    Stock restock(RestockInput input) {
        UUID operationId = input.requestKey() == null ? UUID.randomUUID() : input.requestKey();
        String payload = "RESTOCK|" + input.sku() + "|" + input.warehouseCode() + "|" + input.quantity();
        return idempotentMutation(operationId, input.requestKey(), "RESTOCK", payload,
            () -> {
            Stock before = store.stock(input.sku(), input.warehouseCode());
            change(before, input.quantity(), 0, 0, 0, 0, MovementType.RESTOCK, null,
                null, null, "Stock restocked", operationId);
            return store.stock(input.sku(), input.warehouseCode());
            }, () -> store.stock(input.sku(), input.warehouseCode()));
    }

    List<Stock> transfer(TransferInput input) {
        if (input.fromWarehouse().equals(input.toWarehouse())) {
            throw ApiProblem.invalid("Choose two different warehouses.");
        }

        UUID operationId = input.requestKey() == null ? UUID.randomUUID() : input.requestKey();
        String payload = "TRANSFER|" + input.sku() + "|" + input.fromWarehouse() + "|"
            + input.toWarehouse() + "|" + input.quantity();
        return idempotentMutation(operationId, input.requestKey(), "TRANSFER", payload, () -> {
            Stock from = store.stock(input.sku(), input.fromWarehouse());
            Stock to = store.stock(input.sku(), input.toWarehouse());
            int quantity = input.quantity();
            if (from.available() < quantity) {
                throw ApiProblem.conflict("INSUFFICIENT_STOCK", "The source warehouse has %d available."
                    .formatted(from.available()));
            }

            if (STOCK_ORDER.compare(from, to) < 0) transferChanges(from, to, quantity, operationId, true);
            else transferChanges(from, to, quantity, operationId, false);
            return List.of(store.stock(input.sku(), input.fromWarehouse()), store.stock(input.sku(), input.toWarehouse()));
        }, () -> List.of(store.stock(input.sku(), input.fromWarehouse()), store.stock(input.sku(), input.toWarehouse())));
    }

    private void transferChanges(Stock from, Stock to, int quantity, UUID operationId, boolean sourceFirst) {
        Runnable source = () -> change(from, -quantity, 0, 0, 0, 0, MovementType.TRANSFER_OUT, null,
            from.warehouseCode(), to.warehouseCode(), "Warehouse transfer", operationId);
        Runnable destination = () -> change(to, quantity, 0, 0, 0, 0, MovementType.TRANSFER_IN, null,
            from.warehouseCode(), to.warehouseCode(), "Warehouse transfer", operationId);
        if (sourceFirst) { source.run(); destination.run(); }
        else { destination.run(); source.run(); }
    }

    Stock adjust(StockAdjustmentInput input) {
        if (input.quantityChange() == 0 || input.quantityChange() < -10_000 || input.quantityChange() > 10_000
            || (input.stockBucket() != StockBucket.AVAILABLE && input.stockBucket() != StockBucket.DAMAGED)) {
            throw ApiProblem.invalid("Manual adjustments are limited to available or damaged stock, up to 10,000 units.");
        }
        UUID operationId = input.requestKey() == null ? UUID.randomUUID() : input.requestKey();
        String payload = "ADJUST|" + input.sku() + "|" + input.warehouseCode() + "|"
            + input.stockBucket() + "|" + input.quantityChange() + "|" + input.reason().strip();
        return idempotentMutation(operationId, input.requestKey(), "STOCK_ADJUSTMENT", payload, () -> {
            Stock before = store.stock(input.sku(), input.warehouseCode());
            long[] deltas = new long[5];
            deltas[input.stockBucket().ordinal()] = input.quantityChange();
            change(before, deltas[0], deltas[1], deltas[2], deltas[3], deltas[4],
                MovementType.STOCK_ADJUSTMENT, null, null, null, input.reason().strip(), operationId);
            return store.stock(input.sku(), input.warehouseCode());
        }, () -> store.stock(input.sku(), input.warehouseCode()));
    }

    Stock recordDamage(DamagedStockInput input) {
        UUID operationId = input.requestKey() == null ? UUID.randomUUID() : input.requestKey();
        String payload = "DAMAGE|" + input.sku() + "|" + input.warehouseCode() + "|"
            + input.quantity() + "|" + input.reason().strip();
        return idempotentMutation(operationId, input.requestKey(), "DAMAGED_STOCK", payload, () -> {
            Stock before = store.stock(input.sku(), input.warehouseCode());
            change(before, -input.quantity(), 0, input.quantity(), 0, 0, MovementType.DAMAGED_STOCK,
                null, null, null, input.reason().strip(), operationId);
            return store.stock(input.sku(), input.warehouseCode());
        }, () -> store.stock(input.sku(), input.warehouseCode()));
    }

    Stock writeOff(WriteOffInput input) {
        UUID operationId = input.requestKey() == null ? UUID.randomUUID() : input.requestKey();
        String payload = "WRITEOFF|" + input.sku() + "|" + input.warehouseCode() + "|"
            + input.quantity() + "|" + input.reason().strip();
        return idempotentMutation(operationId, input.requestKey(), "STOCK_WRITE_OFF", payload, () -> {
            Stock before = store.stock(input.sku(), input.warehouseCode());
            change(before, 0, 0, -input.quantity(), 0, input.quantity(), MovementType.STOCK_WRITE_OFF,
                null, null, null, input.reason().strip(), operationId);
            return store.stock(input.sku(), input.warehouseCode());
        }, () -> store.stock(input.sku(), input.warehouseCode()));
    }

    Stock returnStock(ReturnInput input) {
        UUID operationId = input.requestKey() == null ? UUID.randomUUID() : input.requestKey();
        String payload = "RETURN|" + input.orderId() + "|" + input.sku() + "|" + input.warehouseCode()
            + "|" + input.quantity() + "|" + input.reason().strip();
        return idempotentMutation(operationId, input.requestKey(), "RETURN", payload, () -> {
            Order order = order(input.orderId());
            if (order.status() != OrderStatus.FULFILLED) {
                throw ApiProblem.conflict("ORDER_NOT_FULFILLED", "Only fulfilled order units can be returned.");
            }
            int shipped = order.allocations().stream().filter(item -> item.sku().equals(input.sku())
                && item.warehouseCode().equals(input.warehouseCode())).mapToInt(Allocation::quantity).sum();
            int alreadyReturned = store.returnedQuantity(input.orderId(), input.sku(), input.warehouseCode());
            if (shipped - alreadyReturned < input.quantity()) {
                throw ApiProblem.conflict("RETURN_EXCEEDS_SHIPPED", "The return exceeds this order's remaining shipped quantity.");
            }
            Stock before = store.stock(input.sku(), input.warehouseCode());
            change(before, input.quantity(), 0, 0, -input.quantity(), 0, MovementType.RETURN,
                input.orderId(), null, null, input.reason().strip(), operationId);
            return store.stock(input.sku(), input.warehouseCode());
        }, () -> store.stock(input.sku(), input.warehouseCode()));
    }

    Stock setThreshold(ThresholdInput input) {
        return retry(() -> {
            store.stock(input.sku(), input.warehouseCode());
            store.setThreshold(input.sku(), input.warehouseCode(), input.minimumAvailable());
            return store.stock(input.sku(), input.warehouseCode());
        });
    }

    private <T> T idempotentMutation(UUID operationId, UUID requestKey, String type, String payload,
                                    Supplier<T> mutation, Supplier<T> replay) {
        String hash = payloadHash(payload);
        if (requestKey != null) {
            try {
                return retry(() -> {
                    var existing = store.operationByRequestKey(requestKey);
                    if (existing.isPresent()) {
                        verifyReplay(existing.get(), type, hash);
                        throw new ReplayedOperation();
                    }
                    store.insertOperation(operationId, requestKey, type, hash, now());
                    return mutation.get();
                });
            } catch (ReplayedOperation | DuplicateKeyException duplicate) {
                var existing = store.operationByRequestKey(requestKey).orElseThrow(
                    () -> ApiProblem.busy("The stock operation could not be confirmed. Retry with the same request key."));
                verifyReplay(existing, type, hash);
                return replay.get();
            }
        }
        return retry(() -> {
            store.insertOperation(operationId, null, type, hash, now());
            return mutation.get();
        });
    }

    private void verifyReplay(Store.InventoryOperation operation, String type, String hash) {
        if (!operation.operationType().equals(type) || !operation.payloadHash().equals(hash)) {
            throw ApiProblem.conflict("IDEMPOTENCY_MISMATCH", "This request key was already used for a different stock operation.");
        }
    }

    private static String payloadHash(String payload) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Order order(UUID id) {
        return store.orderHeader(id).withAllocations(store.allocations(id));
    }

    Order cancel(UUID id) {
        return retry(() -> {
            boolean changed = completeOrder(id, OrderStatus.CANCELLED);
            Order order = order(id);
            if (!changed && order.status() == OrderStatus.FULFILLED) {
                throw ApiProblem.conflict("ALREADY_FULFILLED", "This order has already been fulfilled.");
            }
            return order;
        });
    }

    boolean fulfill(UUID id) {
        return retry(() -> completeOrder(id, OrderStatus.FULFILLED));
    }

    private boolean completeOrder(UUID id, OrderStatus next) {
        OffsetDateTime now = now();
        if (!store.transition(id, next, now)) {
            return false;
        }
        UUID operationId = UUID.nameUUIDFromBytes((id + ":" + next).getBytes(StandardCharsets.UTF_8));
        store.insertOperation(operationId, null, next == OrderStatus.CANCELLED ? "RESERVATION_RELEASE" : "FULFILLMENT",
            payloadHash(id + "|" + next), now);
        boolean cancelled = next == OrderStatus.CANCELLED;
        for (Allocation allocation : store.allocations(id)) {
            Stock stock = store.stock(allocation.sku(), allocation.warehouseCode());
            int quantity = allocation.quantity();
            change(stock, cancelled ? quantity : 0, -quantity, 0, cancelled ? 0 : quantity, 0,
                cancelled ? MovementType.RESERVATION_RELEASE : MovementType.FULFILLMENT, id,
                null, null, cancelled ? "Order cancellation released reserved stock" : "Order fulfilled", operationId);
        }
        store.finishEvent(id, cancelled ? "CANCELLED" : "PROCESSED", now);
        return true;
    }

    private void change(Stock before, long available, long reserved, long damaged, long shipped, long writtenOff,
                        MovementType type, UUID referenceOrderId, String sourceWarehouse,
                        String destinationWarehouse, String reason, UUID operationId) {
        long[] deltas = {available, reserved, damaged, shipped, writtenOff};
        long[] previous = {before.available(), before.reserved(), before.damaged(), before.shipped(), before.writtenOff()};
        for (int index = 0; index < deltas.length; index++) {
            if (deltas[index] < 0 && previous[index] < -deltas[index]) {
                throw ApiProblem.conflict("INSUFFICIENT_STOCK", "The %s bucket does not contain enough units."
                    .formatted(StockBucket.values()[index].name().toLowerCase()));
            }
        }
        if (!store.updateStock(before, available, reserved, damaged, shipped, writtenOff)) {
            throw new StaleStock();
        }
        StockBucket[] buckets = StockBucket.values();
        OffsetDateTime timestamp = now();
        for (int index = 0; index < deltas.length; index++) {
            if (deltas[index] == 0) continue;
            store.insertMovement(new InventoryMovement(UUID.randomUUID(), before.sku(), before.warehouseCode(),
                type, buckets[index], deltas[index], previous[index], previous[index] + deltas[index],
                referenceOrderId, sourceWarehouse, destinationWarehouse, reason, "system", timestamp, operationId));
        }
    }

    private <T> T retry(Supplier<T> operation) {
        return retry(operation, () -> {});
    }

    private <T> T retry(Supplier<T> operation, Runnable onVersionConflict) {
        return retry(operation, onVersionConflict, () -> {});
    }

    private <T> T retry(Supplier<T> operation, Runnable onVersionConflict, Runnable onRetry) {
        return retry(operation, onVersionConflict, onRetry, ignored -> {});
    }

    private <T> T retry(Supplier<T> operation, Runnable onVersionConflict, Runnable onRetry,
                        LongConsumer onTransactionNanos) {
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            boolean retryable = false;
            try {
                long transactionStarted = System.nanoTime();
                try {
                    return Objects.requireNonNull(tx.execute(status -> operation.get()));
                } finally {
                    onTransactionNanos.accept(System.nanoTime() - transactionStarted);
                }
            } catch (StaleStock stale) {
                onVersionConflict.run();
                retryable = true;
            } catch (ConcurrencyFailureException contention) {
                // A database lock timeout or deadlock also requires a fresh transaction.
                retryable = true;
            } catch (QueryTimeoutException lockTimeout) {
                if (isRetryableLockTimeout(lockTimeout)) retryable = true;
                else throw lockTimeout;
            } catch (DataAccessResourceFailureException unavailable) {
                throw ApiProblem.databaseUnavailable("The inventory database is temporarily unavailable.");
            }

            if (!retryable || attempt == retryMaxAttempts || Thread.currentThread().isInterrupted()) {
                break;
            }
            onRetry.run();
            LockSupport.parkNanos(fullJitterNanos(attempt));
            if (Thread.currentThread().isInterrupted()) break;
        }
        throw ApiProblem.busy("Inventory is busy. Retry this request.");
    }

    private long fullJitterNanos(int failedAttempt) {
        long cap = retryBaseDelay.toNanos();
        long maximum = retryMaxDelay.toNanos();
        for (int exponent = 1; exponent < failedAttempt && cap < maximum; exponent++) {
            cap = cap > maximum / 2 ? maximum : Math.min(maximum, cap * 2);
        }
        return ThreadLocalRandom.current().nextLong(cap + 1);
    }

    private boolean isRetryableLockTimeout(QueryTimeoutException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                && (sql.getErrorCode() == 50200 || "40001".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    private static String fingerprint(String customer, Map<String, Integer> lines, String preferredWarehouse) {
        try {
            String warehouse = preferredWarehouse == null ? "" : "|" + preferredWarehouse.length() + ":" + preferredWarehouse;
            String canonical = customer.length() + ":" + customer + warehouse + lines;
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
