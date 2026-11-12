package in.stockline.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

public final class Models {
    public static final String SKU_PATTERN = "[A-Z0-9][A-Z0-9-]{0,23}";
    public static final String WAREHOUSE_PATTERN = "[A-Z0-9][A-Z0-9-]{0,15}";

    private Models() {}

    public enum OrderStatus { RESERVED, FULFILLED, CANCELLED }
    public enum Role { ADMIN, WAREHOUSE_MANAGER }
    public enum EventType {
        ORDER_RESERVED, ORDER_FULFILLED, ORDER_CANCELLED, LOW_STOCK,
        INVENTORY_ADJUSTED, STOCK_TRANSFERRED, RESERVATION_EXPIRED
    }
    public enum EventStatus { PENDING, PROCESSING, RETRY_WAIT, COMPLETED, DEAD }
    public enum WarehouseStatus { ACTIVE, INACTIVE, MAINTENANCE }
    public enum AllocationStrategy {
        SINGLE_WAREHOUSE, NEAREST_WAREHOUSE, FEWEST_SPLITS, MAXIMUM_AVAILABILITY, COST_OPTIMIZED
    }

    public record Product(String sku, String name, String category) {}
    public record Warehouse(String code, String name, String city, int priority, WarehouseStatus status,
                            Double latitude, Double longitude, BigDecimal handlingCost,
                            BigDecimal shippingCostPerUnit, Integer maxFulfillmentCapacity) {
        public Warehouse(String code, String name, String city, int priority) {
            this(code, name, city, priority, WarehouseStatus.ACTIVE, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }
    public enum MovementType {
        INITIAL_STOCK, RESTOCK, RESERVATION, RESERVATION_RELEASE, FULFILLMENT,
        TRANSFER_OUT, TRANSFER_IN, STOCK_ADJUSTMENT, DAMAGED_STOCK, RETURN, STOCK_WRITE_OFF
    }
    public enum StockBucket { AVAILABLE, RESERVED, DAMAGED, SHIPPED, WRITTEN_OFF }
    public enum AvailabilityStatus { AVAILABLE, OUT_OF_STOCK }

    public record Stock(String sku, String warehouseCode, long available, long reserved, long damaged,
                        long shipped, long writtenOff, long minimumAvailable, long version) {
        public Stock(String sku, String warehouseCode, long available, long reserved, long shipped, long version) {
            this(sku, warehouseCode, available, reserved, 0, shipped, 0, 5, version);
        }
    }
    public record WarehouseBalance(String warehouseCode, String warehouse, long totalStock, long available,
                                   long reserved, long damaged, long shipped, long writtenOff,
                                   long minimumAvailable, boolean belowThreshold, WarehouseStatus warehouseStatus,
                                   AvailabilityStatus status) {}
    public record ProductInventory(String sku, String product, long totalStock, long available, long reserved,
                                   long damaged, long shipped, long writtenOff, int stockingWarehouses,
                                   List<String> belowThresholdWarehouses, AvailabilityStatus status,
                                   List<WarehouseBalance> warehouses) {}
    public record NetworkInventorySummary(long totalPhysical, long available, long reserved, long damaged,
                                          long shipped, long writtenOff, long products, long stockedWarehouses,
                                          long belowThresholdRows) {}
    public record WarehouseInventorySummary(String warehouseCode, String warehouse, WarehouseStatus status,
                                            long totalPhysical, long available, long reserved, long damaged,
                                            long shipped, long writtenOff, long products, long belowThresholdProducts) {}
    public record AllocationDecision(long allocationId, UUID orderId, String sku, String warehouseCode,
                                     String strategy, String status, BigDecimal estimatedCost,
                                     BigDecimal estimatedDistanceKm, String reason, String decisionMetadata,
                                     OffsetDateTime createdAt) {}
    public record Allocation(String sku, String warehouseCode, int quantity) {}
    public record Order(UUID id, String customer, OrderStatus status, OffsetDateTime createdAt,
                        OffsetDateTime updatedAt, List<Allocation> allocations) {}
    public record Placement(Order order, boolean replayed) {}
    public record Catalog(List<Product> products, List<Warehouse> warehouses) {}
    public record Overview(long available, long reserved, long damaged, long shipped, long writtenOff,
                           long physical, long products, int stockRows, int lowStockRows, int lowStockProducts,
                           long orders, long pendingOrders, long fulfilledOrders, long cancelledOrders) {}
    public record OutboxEvent(UUID eventId, String eventType, String aggregateType, String aggregateId,
                              String payload, OffsetDateTime createdAt, String status, int attemptCount,
                              OffsetDateTime nextAttemptAt, String lastError, String failureKind,
                              OffsetDateTime processingAt, String claimedBy, OffsetDateTime leaseUntil,
                              OffsetDateTime processedAt, UUID correlationId, String dedupeKey,
                              UUID requestCorrelationId) {
        public UUID orderId() {
            return "ORDER".equals(aggregateType) ? UUID.fromString(aggregateId) : null;
        }
        public OffsetDateTime dueAt() { return nextAttemptAt; }
        public boolean retryable() { return "RETRYABLE".equals(failureKind); }
    }
    public record EventAttempt(UUID attemptId, UUID eventId, int attemptNumber, String workerId,
                               OffsetDateTime startedAt, OffsetDateTime completedAt, String outcome,
                               String errorMessage) {}
    public record ReplenishmentSuggestion(UUID suggestionId, UUID eventId, String sku, String product,
                                          String warehouseCode, String warehouse, long available,
                                          long minimumAvailable, long recommendedQuantity, String status,
                                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record EventMetrics(long pending, long processing, long retryWait, long completed, long dead,
                               long totalAttempts, double averageProcessingMillis,
                               long oldestPendingAgeMillis) {}
    public record WorkerHealth(String instanceId, int configuredWorkers, int activeWorkers, int queuedTasks,
                               OffsetDateTime lastPollAt, OffsetDateTime lastCompletedAt,
                               String lastFailure) {}
    public record InventoryMovement(UUID movementId, String productId, String warehouseId,
                                   MovementType movementType, StockBucket stockBucket, long quantityChange,
                                   long previousStock, long newStock, UUID referenceOrderId,
                                   String sourceWarehouse, String destinationWarehouse, String reason,
                                   String actor, OffsetDateTime timestamp, UUID operationId,
                                   UUID actorUserId, String actorRole) {
        public InventoryMovement(UUID movementId, String productId, String warehouseId,
                                 MovementType movementType, StockBucket stockBucket, long quantityChange,
                                 long previousStock, long newStock, UUID referenceOrderId,
                                 String sourceWarehouse, String destinationWarehouse, String reason,
                                 String actor, OffsetDateTime timestamp, UUID operationId) {
            this(movementId, productId, warehouseId, movementType, stockBucket, quantityChange,
                previousStock, newStock, referenceOrderId, sourceWarehouse, destinationWarehouse,
                reason, actor, timestamp, operationId, null, null);
        }
    }
    public record LowStockAlert(String sku, String product, String warehouseCode, String warehouse,
                                long available, long minimumAvailable, long replenishmentUnits,
                                boolean outOfStock) {}
    public record ReconciliationLine(String sku, String product, String warehouseCode, String warehouse,
                                     StockBucket stockBucket, long expectedQuantity, long actualQuantity,
                                     long difference, String status, String ledgerChainStatus,
                                     int invalidMovementCount, int discontinuityCount, int missingOperationCount,
                                     int unbalancedTransferCount, int unbalancedOperationCount,
                                     boolean bucketBalanceMismatch, String overallIntegrityStatus) {
        public ReconciliationLine(String sku, String product, String warehouseCode, String warehouse,
                                  StockBucket stockBucket, long expectedQuantity, long actualQuantity,
                                  long difference, String status) {
            this(sku, product, warehouseCode, warehouse, stockBucket, expectedQuantity, actualQuantity,
                difference, status, status.equals("MATCH") ? "VALID" : "BALANCE_MISMATCH",
                0, 0, 0, 0, 0, !status.equals("MATCH"), status.equals("MATCH") ? "VALID" : "INVALID");
        }
    }

    public record UserAccount(UUID id, String username, String passwordHash, Role role,
                              boolean enabled, OffsetDateTime createdAt, List<String> warehouseCodes) {}

}
