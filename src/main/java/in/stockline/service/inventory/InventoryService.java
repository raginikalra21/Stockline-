package in.stockline.service.inventory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.LongConsumer;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.persistence.repository.Store;
import static in.stockline.api.dto.InventoryRequests.*;
import static in.stockline.domain.model.Models.*;
import in.stockline.security.CurrentActorProvider;
import in.stockline.security.WarehouseAccessService;
import in.stockline.service.allocation.AllocationEngine;
import in.stockline.domain.model.AllocationModels.AllocationPreview;
import in.stockline.domain.model.AllocationModels.AllocationLineSummary;
import in.stockline.domain.model.AllocationModels.ProposedAllocation;
import in.stockline.domain.model.AllocationModels.AllocationCandidate;
import in.stockline.domain.model.AllocationModels.AllocationStrategyConfiguration;
import in.stockline.observability.StocklineMetrics;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
public class InventoryService {
    private static final Comparator<Stock> STOCK_ORDER = Comparator.comparing(Stock::sku)
        .thenComparing(Stock::warehouseCode);
    private final Store store;
    private final RetryExecutor retryExecutor;
    private final Duration fulfillmentDelay;
    private final CurrentActorProvider actorProvider;
    private final WarehouseAccessService warehouseAccess;
    private final Duration reservationTtl;
    private final AllocationEngine allocationEngine;
    private final StocklineMetrics metrics;

    public record RetrySettings(int maxAttempts, Duration baseDelay, Duration maxDelay) {}

    @Autowired
    public InventoryService(Store store, RetryExecutor retryExecutor,
                            @Value("${stockline.fulfillment-delay:8s}") Duration fulfillmentDelay,
                            CurrentActorProvider actorProvider, WarehouseAccessService warehouseAccess,
                            @Value("${stockline.reservation-ttl:30m}") Duration reservationTtl,
                            AllocationEngine allocationEngine, StocklineMetrics metrics) {
        this.store = store;
        this.retryExecutor = retryExecutor;
        this.fulfillmentDelay = fulfillmentDelay;
        this.actorProvider = actorProvider;
        this.warehouseAccess = warehouseAccess;
        this.reservationTtl = reservationTtl;
        this.allocationEngine = allocationEngine;
        this.metrics = metrics;
    }

    public InventoryService(Store store, RetryExecutor retryExecutor, Duration fulfillmentDelay) {
        this(store, retryExecutor, fulfillmentDelay, new CurrentActorProvider(), null, Duration.ofHours(24),
            new AllocationEngine(), new StocklineMetrics());
    }

    public InventoryService(Store store, PlatformTransactionManager manager, Duration fulfillmentDelay) {
        this(store, manager, fulfillmentDelay,
            new RetrySettings(8, Duration.ofMillis(2), Duration.ofMillis(100)));
    }

    public InventoryService(Store store, PlatformTransactionManager manager, Duration fulfillmentDelay, RetrySettings retry) {
        this(store, new RetryExecutor(manager, retry.maxAttempts(), retry.baseDelay(), retry.maxDelay()), fulfillmentDelay);
    }
    private static class ExistingRequest extends DuplicateKeyException {
        ExistingRequest() { super("The order request key already exists."); }
    }
    private static class ReplayedOperation extends RuntimeException {}

    public Placement place(OrderInput input) {
        try {
            return place(input, () -> {}, () -> {}, () -> {}, ignored -> {}, true);
        } catch (ApiProblem failure) {
            metrics.reservationRejected(failure.code);
            metrics.allocationFailure("reservation", failure.code);
            throw failure;
        }
    }

    // Simulation-only overload: callers use it only with disposable H2 data, never live reservations.
    public Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict) {
        return place(input, firstPlanReady, onVersionConflict, () -> {}, ignored -> {}, false);
    }

    public Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict, Runnable onRetry) {
        return place(input, firstPlanReady, onVersionConflict, onRetry, ignored -> {}, false);
    }

    public Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict, Runnable onRetry,
                     LongConsumer onTransactionNanos) {
        return place(input, firstPlanReady, onVersionConflict, onRetry, onTransactionNanos, false);
    }

    private Placement place(OrderInput input, Runnable firstPlanReady, Runnable onVersionConflict, Runnable onRetry,
                     LongConsumer onTransactionNanos, boolean lockWarehouseSettings) {
        var lines = new TreeMap<String, Integer>();
        input.lines().forEach(line -> lines.merge(line.sku(), line.quantity(), Integer::sum));
        String customer = input.customer().strip();
        String preferredWarehouse = input.preferredWarehouseCode();
        validateCoordinates(input.deliveryLatitude(), input.deliveryLongitude());
        String fingerprint = fingerprint(customer, lines, preferredWarehouse, input.allocationStrategy(),
            input.deliveryLatitude(), input.deliveryLongitude());
        UUID id = UUID.randomUUID();
        var finalPlan = new java.util.concurrent.atomic.AtomicReference<AllocationPreview>();
        var planningNanos = new java.util.concurrent.atomic.AtomicLong();

        try {
            var firstAttempt = new java.util.concurrent.atomic.AtomicBoolean(true);
            Placement placement = retry(() -> {
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

                AllocationPreview preview;
                List<AllocationCandidate> candidates = List.of();
                try {
                    if (preferredWarehouse != null) {
                        if (!store.warehouseExists(preferredWarehouse)) throw ApiProblem.notFound("That warehouse does not exist.");
                        requireWarehouse(preferredWarehouse);
                    }
                    AllocationStrategy strategy = input.allocationStrategy() == null
                        ? store.defaultAllocationStrategy() : input.allocationStrategy();
                    candidates = authorizedCandidates(new ArrayList<>(lines.keySet()));
                    ensureKnownProducts(lines, candidates);
                    if (lockWarehouseSettings) {
                        // Lock every eligible configuration in one stable order. This keeps status,
                        // capacity, and cost inputs fixed while the final plan is checked and reserved.
                        store.lockWarehouseConfigurations(candidates.stream()
                            .map(candidate -> candidate.warehouse().code()).distinct().sorted().toList());
                        candidates = authorizedCandidates(new ArrayList<>(lines.keySet()));
                    }
                    long planningStarted = System.nanoTime();
                    preview = allocationEngine.preview(lines, strategy, candidates, preferredWarehouse,
                        input.deliveryLatitude(), input.deliveryLongitude());
                    planningNanos.set(System.nanoTime() - planningStarted);
                    finalPlan.set(preview);
                    if (!preview.fullyFulfillable()) throw insufficientAllocation(preview);
                } catch (ApiProblem rejected) {
                    if (firstAttempt.getAndSet(false)) firstPlanReady.run();
                    throw rejected;
                }
                if (firstAttempt.getAndSet(false)) firstPlanReady.run();
                for (ProposedAllocation proposed : preview.allocations()) {
                    AllocationCandidate snapshot = candidate(candidates, proposed.sku(), proposed.warehouseCode());
                    if (snapshot == null) throw ApiProblem.conflict("ALLOCATION_CHANGED", "A selected warehouse is no longer eligible.");
                    Stock stock = snapshot.stock();
                    int quantity = proposed.proposedQuantity();
                    change(stock, -quantity, quantity, 0, 0, 0, MovementType.RESERVATION, id,
                        null, null, "Customer order reservation", id);
                    Map<String, Object> metadata = new java.util.LinkedHashMap<>();
                    metadata.put("schemaVersion", 1);
                    metadata.put("availableBefore", proposed.availableBefore());
                    metadata.put("requestedQuantity", proposed.requestedQuantity());
                    metadata.put("preferredWarehouse", preferredWarehouse);
                    metadata.put("deliveryLatitude", input.deliveryLatitude());
                    metadata.put("deliveryLongitude", input.deliveryLongitude());
                    store.insertAllocation(id, new Allocation(stock.sku(), stock.warehouseCode(), quantity),
                        preview.strategy(), proposed.estimatedCost(), proposed.estimatedDistanceKm(),
                        proposed.reason(), metadata, created);
                }
                OffsetDateTime eventAt = now();
                store.enqueueEvent(UUID.randomUUID(), EventType.ORDER_RESERVED, "ORDER", id.toString(),
                    Map.of("schemaVersion", 1, "orderId", id), id, "ORDER_RESERVED:" + id,
                    eventAt, eventAt.plus(fulfillmentDelay));
                store.enqueueEvent(UUID.randomUUID(), EventType.RESERVATION_EXPIRED, "ORDER", id.toString(),
                    Map.of("schemaVersion", 1, "orderId", id), id, "RESERVATION_EXPIRED:" + id,
                    eventAt, eventAt.plus(reservationTtl));
                return new Placement(order(id), false);
            }, onVersionConflict, onRetry, onTransactionNanos);
            if (lockWarehouseSettings && !placement.replayed() && finalPlan.get() != null) {
                AllocationPreview plan = finalPlan.get();
                long duration = planningNanos.get();
                int warehouseCount = plan.warehousesInvolved();
                metrics.afterCommit(() -> metrics.reservationCreated(plan.strategy().name(), warehouseCount, duration));
            }
            return placement;
        } catch (ApiProblem failure) {
            AllocationPreview plan = finalPlan.get();
            if (lockWarehouseSettings && plan != null)
                metrics.allocationFailedPlan(plan.strategy().name(), planningNanos.get());
            throw failure;
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

    private List<AllocationCandidate> authorizedCandidates(List<String> skus) {
        List<AllocationCandidate> candidates = store.allocationCandidates(skus);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserAccount user && warehouseAccess != null)
            return candidates.stream().filter(c -> warehouseAccess.allowed(user, c.warehouse().code())).toList();
        return candidates;
    }

    private void ensureKnownProducts(Map<String, Integer> demand, List<AllocationCandidate> candidates) {
        for (String sku : demand.keySet()) if (candidates.stream().noneMatch(candidate -> candidate.stock().sku().equals(sku)))
            throw ApiProblem.notFound("Unknown SKU " + sku + ".");
    }

    private AllocationCandidate candidate(List<AllocationCandidate> candidates, String sku, String warehouse) {
        return candidates.stream().filter(c -> c.stock().sku().equals(sku)
            && c.warehouse().code().equals(warehouse)).findFirst().orElse(null);
    }

    private ApiProblem insufficientAllocation(AllocationPreview preview) {
        String missing = preview.lines().stream().filter(line -> !line.fullyFulfillable())
            .map(line -> line.sku() + " needs " + (line.requestedQuantity() - line.proposedQuantity()) + " more unit(s)")
            .collect(java.util.stream.Collectors.joining("; "));
        return ApiProblem.conflict("INSUFFICIENT_STOCK", "The selected allocation strategy cannot fulfill the order: " + missing + ".");
    }

    private void validateCoordinates(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null))
            throw ApiProblem.invalid("Provide both delivery latitude and longitude, or leave both empty.");
    }

    public AllocationPreview previewAllocation(AllocationPreviewInput input) {
        TreeMap<String, Integer> demand = new TreeMap<>();
        input.lines().forEach(line -> demand.merge(line.sku(), line.quantity(), Integer::sum));
        validateCoordinates(input.deliveryLatitude(), input.deliveryLongitude());
        validatePreferredWarehouse(input.preferredWarehouseCode());
        List<AllocationCandidate> candidates = authorizedCandidates(new ArrayList<>(demand.keySet()));
        ensureKnownProducts(demand, candidates);
        AllocationStrategy strategy = input.allocationStrategy() == null
            ? store.defaultAllocationStrategy() : input.allocationStrategy();
        long started = System.nanoTime();
        AllocationPreview preview = allocationEngine.preview(demand, strategy, candidates, input.preferredWarehouseCode(),
            input.deliveryLatitude(), input.deliveryLongitude());
        metrics.allocationPreview(strategy.name(), System.nanoTime() - started, preview.fullyFulfillable());
        return preview;
    }

    private void validatePreferredWarehouse(String warehouseCode) {
        if (warehouseCode == null) return;
        if (!store.warehouseExists(warehouseCode)) throw ApiProblem.notFound("That warehouse does not exist.");
        requireWarehouse(warehouseCode);
    }

    public AllocationStrategyConfiguration allocationStrategyConfiguration() {
        return new AllocationStrategyConfiguration(store.defaultAllocationStrategy(), List.of(AllocationStrategy.values()));
    }

    public AllocationStrategy setDefaultAllocationStrategy(AllocationStrategy strategy) {
        return retry(() -> {
            store.setDefaultAllocationStrategy(strategy, now());
            return strategy;
        });
    }

    public Warehouse updateWarehouseConfiguration(String code, WarehouseConfigurationInput input) {
        validateCoordinates(input.latitude(), input.longitude());
        return retry(() -> {
            store.lockWarehouseConfigurations(List.of(code));
            store.updateWarehouseConfiguration(code, input.status(), input.latitude(), input.longitude(),
                input.handlingCost(), input.shippingCostPerUnit(), input.maxFulfillmentCapacity());
            return store.warehouse(code);
        });
    }

    public Warehouse warehouseConfiguration(String code) { return store.warehouse(code); }

    public List<AllocationDecision> allocationDecisions(UUID orderId) {
        return store.allocationDecisions(orderId);
    }

    public Product addProduct(ProductInput input) {
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

    public Warehouse addWarehouse(WarehouseInput input) {
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

    public Stock restock(RestockInput input) {
        requireWarehouse(input.warehouseCode());
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

    public List<Stock> transfer(TransferInput input) {
        requireWarehouse(input.fromWarehouse()); requireWarehouse(input.toWarehouse());
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
            OffsetDateTime eventAt = now();
            store.enqueueEvent(UUID.randomUUID(), EventType.STOCK_TRANSFERRED, "INVENTORY", input.sku(),
                Map.of("schemaVersion", 1, "sku", input.sku(), "sourceWarehouse", input.fromWarehouse(),
                    "destinationWarehouse", input.toWarehouse(), "quantity", quantity), operationId,
                "STOCK_TRANSFERRED:" + operationId, eventAt, eventAt);
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

    public Stock adjust(StockAdjustmentInput input) {
        requireWarehouse(input.warehouseCode());
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
            OffsetDateTime eventAt = now();
            store.enqueueEvent(UUID.randomUUID(), EventType.INVENTORY_ADJUSTED, "INVENTORY", input.sku(),
                Map.of("schemaVersion", 1, "sku", input.sku(), "warehouseCode", input.warehouseCode(),
                    "stockBucket", input.stockBucket().name(), "quantityChange", input.quantityChange(),
                    "reason", input.reason().strip()), operationId, "INVENTORY_ADJUSTED:" + operationId, eventAt, eventAt);
            return store.stock(input.sku(), input.warehouseCode());
        }, () -> store.stock(input.sku(), input.warehouseCode()));
    }

    public Stock recordDamage(DamagedStockInput input) {
        requireWarehouse(input.warehouseCode());
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

    public Stock writeOff(WriteOffInput input) {
        requireWarehouse(input.warehouseCode());
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

    public Stock returnStock(ReturnInput input) {
        requireWarehouse(input.warehouseCode());
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

    public Stock setThreshold(ThresholdInput input) {
        requireWarehouse(input.warehouseCode());
        return retry(() -> {
            Stock before = store.stock(input.sku(), input.warehouseCode());
            store.setThreshold(input.sku(), input.warehouseCode(), input.minimumAvailable());
            store.synchronizeLowStockState(input.sku(), input.warehouseCode(), before.available(),
                input.minimumAvailable(), UUID.randomUUID(), now());
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
                    T result = mutation.get();
                    metrics.afterCommit(() -> metrics.inventoryMutation(type));
                    return result;
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
            T result = mutation.get();
            metrics.afterCommit(() -> metrics.inventoryMutation(type));
            return result;
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

    public Order order(UUID id) {
        return store.orderHeader(id).withAllocations(store.allocations(id));
    }

    public Order cancel(UUID id) {
        return retry(() -> {
            for (Allocation allocation : store.allocations(id)) requireWarehouse(allocation.warehouseCode());
            boolean changed = completeOrder(id, OrderStatus.CANCELLED, false);
            Order order = order(id);
            if (!changed && order.status() == OrderStatus.FULFILLED) {
                throw ApiProblem.conflict("ALREADY_FULFILLED", "This order has already been fulfilled.");
            }
            return order;
        });
    }

    public boolean fulfill(UUID id) {
        return retry(() -> completeOrder(id, OrderStatus.FULFILLED, false));
    }

    public boolean expireReservation(UUID id) {
        return retry(() -> completeOrder(id, OrderStatus.CANCELLED, true));
    }

    private boolean completeOrder(UUID id, OrderStatus next, boolean expired) {
        OffsetDateTime now = now();
        if (!store.transition(id, next, now)) {
            return false;
        }
        var createdAt = store.orderHeader(id).createdAt();
        Duration fulfillmentAge = Duration.between(createdAt, now);
        String metricOutcome = expired ? "expired" : next == OrderStatus.FULFILLED ? "fulfilled" : "cancelled";
        metrics.afterCommit(() -> metrics.orderTransition(metricOutcome,
            "fulfilled".equals(metricOutcome) ? fulfillmentAge : null));
        String transitionName = expired ? "EXPIRED" : next.name();
        UUID operationId = UUID.nameUUIDFromBytes((id + ":" + transitionName).getBytes(StandardCharsets.UTF_8));
        store.insertOperation(operationId, null, next == OrderStatus.CANCELLED ? "RESERVATION_RELEASE" : "FULFILLMENT",
            payloadHash(id + "|" + next), now);
        boolean cancelled = next == OrderStatus.CANCELLED;
        for (Allocation allocation : store.allocations(id)) {
            Stock stock = store.stock(allocation.sku(), allocation.warehouseCode());
            int quantity = allocation.quantity();
            change(stock, cancelled ? quantity : 0, -quantity, 0, cancelled ? 0 : quantity, 0,
                cancelled ? MovementType.RESERVATION_RELEASE : MovementType.FULFILLMENT, id,
                null, null, expired ? "Reservation expired and released stock"
                    : cancelled ? "Order cancellation released reserved stock" : "Order fulfilled", operationId,
                !cancelled);
        }
        if (!expired) {
            EventType type = cancelled ? EventType.ORDER_CANCELLED : EventType.ORDER_FULFILLED;
            store.enqueueEvent(UUID.randomUUID(), type, "ORDER", id.toString(),
                Map.of("schemaVersion", 1, "orderId", id), id, type.name() + ":" + id, now, now);
        } else {
            store.enqueueEvent(UUID.randomUUID(), EventType.ORDER_CANCELLED, "ORDER", id.toString(),
                Map.of("schemaVersion", 1, "orderId", id, "reason", "RESERVATION_EXPIRED"), id,
                "ORDER_CANCELLED:" + id, now, now);
        }
        store.completeUnclaimedEvent("ORDER_RESERVED:" + id, now);
        store.completeUnclaimedEvent("RESERVATION_EXPIRED:" + id, now);
        return true;
    }

    private void change(Stock before, long available, long reserved, long damaged, long shipped, long writtenOff,
                        MovementType type, UUID referenceOrderId, String sourceWarehouse,
                        String destinationWarehouse, String reason, UUID operationId) {
        change(before, available, reserved, damaged, shipped, writtenOff, type, referenceOrderId,
            sourceWarehouse, destinationWarehouse, reason, operationId, false);
    }

    private void change(Stock before, long available, long reserved, long damaged, long shipped, long writtenOff,
                        MovementType type, UUID referenceOrderId, String sourceWarehouse,
                        String destinationWarehouse, String reason, UUID operationId, boolean systemActor) {
        long[] deltas = {available, reserved, damaged, shipped, writtenOff};
        long[] previous = {before.available(), before.reserved(), before.damaged(), before.shipped(), before.writtenOff()};
        for (int index = 0; index < deltas.length; index++) {
            if (deltas[index] < 0 && previous[index] < -deltas[index]) {
                throw ApiProblem.conflict("INSUFFICIENT_STOCK", "The %s bucket does not contain enough units."
                    .formatted(StockBucket.values()[index].name().toLowerCase()));
            }
        }
        if (!store.updateStock(before, available, reserved, damaged, shipped, writtenOff)) {
            throw new RetryExecutor.VersionConflict();
        }
        if (available < 0 && previous[0] > 0 && previous[0] + available == 0)
            metrics.afterCommit(metrics::stockout);
        StockBucket[] buckets = StockBucket.values();
        OffsetDateTime timestamp = now();
        var actor = systemActor ? new CurrentActorProvider.Actor(null, "SYSTEM", null) : actorProvider.current();
        for (int index = 0; index < deltas.length; index++) {
            if (deltas[index] == 0) continue;
            store.insertMovement(new InventoryMovement(UUID.randomUUID(), before.sku(), before.warehouseCode(),
                type, buckets[index], deltas[index], previous[index], previous[index] + deltas[index],
                referenceOrderId, sourceWarehouse, destinationWarehouse, reason, actor.username(), timestamp, operationId,
                actor.userId(), actor.role()));
        }
        store.synchronizeLowStockState(before.sku(), before.warehouseCode(),
            previous[0] + available, before.minimumAvailable(), operationId, timestamp);
    }

    private void requireWarehouse(String warehouse) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserAccount user && warehouseAccess != null)
            warehouseAccess.require(user, warehouse);
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
        return retryExecutor.execute(operation, onVersionConflict, onRetry, onTransactionNanos);
    }

    private static String fingerprint(String customer, Map<String, Integer> lines, String preferredWarehouse,
                                      AllocationStrategy strategy, Double deliveryLatitude, Double deliveryLongitude) {
        try {
            String warehouse = preferredWarehouse == null ? "" : "|" + preferredWarehouse.length() + ":" + preferredWarehouse;
            String canonical = customer.length() + ":" + customer + warehouse + "|" + strategy + "|"
                + deliveryLatitude + "|" + deliveryLongitude + lines;
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
