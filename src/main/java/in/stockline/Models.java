package in.stockline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class Models {
    public static final String SKU_PATTERN = "[A-Z0-9][A-Z0-9-]{0,23}";
    public static final String WAREHOUSE_PATTERN = "[A-Z0-9][A-Z0-9-]{0,15}";

    private Models() {}

    public enum OrderStatus { RESERVED, FULFILLED, CANCELLED }

    public record Product(String sku, String name, String category) {}
    public record Warehouse(String code, String name, String city, int priority) {}
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
                                   long reserved, long damaged, AvailabilityStatus status) {}
    public record ProductInventory(String sku, String product, long totalStock, long available, long reserved,
                                   long damaged, AvailabilityStatus status, List<WarehouseBalance> warehouses) {}
    public record Allocation(String sku, String warehouseCode, int quantity) {}
    public record Order(UUID id, String customer, OrderStatus status, OffsetDateTime createdAt,
                        OffsetDateTime updatedAt, List<Allocation> allocations) {}
    public record Placement(Order order, boolean replayed) {}
    public record Catalog(List<Product> products, List<Warehouse> warehouses) {}
    public record Overview(long available, long reserved, long damaged, long shipped, long writtenOff,
                           long physical, int stockRows, int lowStockRows, int lowStockProducts,
                           long orders, long pendingOrders, long fulfilledOrders, long cancelledOrders) {}
    public record OutboxEvent(UUID orderId, String eventType, String status, OffsetDateTime dueAt,
                              OffsetDateTime processedAt) {}
    public record InventoryMovement(UUID movementId, String productId, String warehouseId,
                                   MovementType movementType, StockBucket stockBucket, long quantityChange,
                                   long previousStock, long newStock, UUID referenceOrderId,
                                   String sourceWarehouse, String destinationWarehouse, String reason,
                                   String actor, OffsetDateTime timestamp, UUID operationId) {}
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

    public record ProductInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                               @NotBlank @Size(max = 80) String name,
                               @NotBlank @Size(max = 40) String category) {}
    public record WarehouseInput(@NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String code,
                                 @NotBlank @Size(max = 80) String name,
                                 @NotBlank @Size(max = 60) String city,
                                 @NotNull @Min(1) @Max(999) Integer priority) {}
    public record OrderLine(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                            @NotNull @Min(1) @Max(10_000) Integer quantity) {}
    public record OrderInput(@NotBlank @Size(max = 80) String customer,
                             @NotNull UUID requestKey,
                             @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderLine> lines,
                             @Pattern(regexp = WAREHOUSE_PATTERN) String preferredWarehouseCode) {
        public OrderInput(String customer, UUID requestKey, List<OrderLine> lines) {
            this(customer, requestKey, lines, null);
        }
    }
    public record RestockInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                               @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String warehouseCode,
                               @NotNull @Min(1) @Max(10_000) Integer quantity, @NotNull UUID requestKey) {
        public RestockInput(String sku, String warehouseCode, Integer quantity) {
            this(sku, warehouseCode, quantity, UUID.randomUUID());
        }
    }
    public record TransferInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                                @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String fromWarehouse,
                                @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String toWarehouse,
                                @NotNull @Min(1) @Max(10_000) Integer quantity, @NotNull UUID requestKey) {
        public TransferInput(String sku, String fromWarehouse, String toWarehouse, Integer quantity) {
            this(sku, fromWarehouse, toWarehouse, quantity, UUID.randomUUID());
        }
    }
    public record StockAdjustmentInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                                       @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String warehouseCode,
                                       @NotNull StockBucket stockBucket, @NotNull Long quantityChange,
                                       @NotBlank @Size(max = 240) String reason, @NotNull UUID requestKey) {}
    public record DamagedStockInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                                    @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String warehouseCode,
                                    @NotNull @Min(1) @Max(10_000) Integer quantity,
                                    @NotBlank @Size(max = 240) String reason, @NotNull UUID requestKey) {}
    public record WriteOffInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                                @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String warehouseCode,
                                @NotNull @Min(1) @Max(10_000) Integer quantity,
                                @NotBlank @Size(max = 240) String reason, @NotNull UUID requestKey) {}
    public record ReturnInput(@NotNull UUID orderId, @NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                              @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String warehouseCode,
                              @NotNull @Min(1) @Max(10_000) Integer quantity,
                              @NotBlank @Size(max = 240) String reason, @NotNull UUID requestKey) {}
    public record ThresholdInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                                 @NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String warehouseCode,
                                 @NotNull @Min(0) @Max(1_000_000) Integer minimumAvailable) {}
    public record RaceInput(@NotNull @Min(2) @Max(1_000) Integer buyers,
                            @NotNull @Min(1) @Max(1_000_000) Integer stock,
                            @NotNull @Min(1) @Max(10_000) Integer quantity,
                            @NotNull @Min(1) @Max(32) Integer warehouses,
                            @NotNull @Min(1) @Max(128) Integer workers,
                            @NotNull @Min(1) @Max(300) Integer durationSeconds,
                            @NotNull @Pattern(regexp = "COMPARE|UNSAFE|NAIVE|OPTIMISTIC|PESSIMISTIC") String mode,
                            @NotNull @Min(1) @Max(5) Integer repetitions) {
        public RaceInput {
            if (warehouses == null) warehouses = 1;
            if (workers == null) workers = buyers == null ? 1 : Math.min(32, buyers);
            if (durationSeconds == null) durationSeconds = 60;
            if (mode == null) mode = "COMPARE";
            if (repetitions == null) repetitions = 1;
        }
        public RaceInput(Integer buyers, Integer stock, Integer quantity, Integer warehouses, Integer workers,
                         Integer durationSeconds, String mode) {
            this(buyers, stock, quantity, warehouses, workers, durationSeconds, mode, 1);
        }
        public RaceInput(Integer buyers, Integer stock, Integer quantity) {
            this(buyers, stock, quantity, 1, Math.min(32, buyers), 60, "COMPARE", 1);
        }
    }
    public record RaceModeResult(String mode, int attempts, int accepted, int rejected, long oversold,
                                 long optimisticConflicts, long retryAttempts, long failedTransactions,
                                 long finalAvailable, long durationMs, double requestsPerSecond,
                                 double averageLatencyMs, double p50LatencyMs, double p95LatencyMs,
                                 double p99LatencyMs, boolean correct, long lostUpdates, long deadlocks,
                                 double lockWaitMs, double queueWaitMs, double databaseTimeMs,
                                 String isolationLevel) {}
    public record RaceRound(int repetition, List<RaceModeResult> strategies) {}
    public record RaceProgress(String status, int total, int completed, int accepted, int rejected,
                               long currentAvailable, long conflicts, long retries, RaceResult result) {}
    public record RaceJob(String id, String status) {}
    public record RaceResult(int buyers, int stock, int quantity, int accepted, int rejected,
                             long optimisticConflicts, long remaining, long reserved, long shipped,
                             boolean verified, RaceModeResult unsafe, RaceModeResult optimistic,
                             RaceModeResult pessimistic, List<RaceRound> rounds) {
        public RaceResult(int buyers, int stock, int quantity, int accepted, int rejected,
                          long optimisticConflicts, long remaining, long reserved, long shipped, boolean verified) {
            this(buyers, stock, quantity, accepted, rejected, optimisticConflicts, remaining,
                reserved, shipped, verified, null, null, null, List.of());
        }
    }
}
