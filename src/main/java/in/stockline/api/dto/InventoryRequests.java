package in.stockline.api.dto;

import in.stockline.domain.model.Models.StockBucket;
import in.stockline.domain.model.Models.AllocationStrategy;
import in.stockline.domain.model.Models.WarehouseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;

import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

import static in.stockline.domain.model.Models.SKU_PATTERN;
import static in.stockline.domain.model.Models.WAREHOUSE_PATTERN;

public final class InventoryRequests {
    private InventoryRequests() {}

    public record ProductInput(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                               @NotBlank @Size(max = 80) String name,
                               @NotBlank @Size(max = 40) String category) {}
    public record WarehouseInput(@NotNull @Pattern(regexp = WAREHOUSE_PATTERN) String code,
                                 @NotBlank @Size(max = 80) String name,
                                 @NotBlank @Size(max = 60) String city,
                                 @NotNull @Min(1) @Max(999) Integer priority) {}
    public record WarehouseConfigurationInput(@NotNull WarehouseStatus status,
                                 @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 2, fraction = 6) Double latitude,
                                 @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 6) Double longitude,
                                 @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal handlingCost,
                                 @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal shippingCostPerUnit,
                                 @Min(1) @Max(1_000_000) Integer maxFulfillmentCapacity) {}
    public record OrderLine(@NotNull @Pattern(regexp = SKU_PATTERN) String sku,
                            @NotNull @Min(1) @Max(10_000) Integer quantity) {}
    public record OrderInput(@NotBlank @Size(max = 80) String customer,
                             @NotNull UUID requestKey,
                             @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderLine> lines,
                             @Pattern(regexp = WAREHOUSE_PATTERN) String preferredWarehouseCode,
                             AllocationStrategy allocationStrategy,
                             @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 2, fraction = 6) Double deliveryLatitude,
                             @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 6) Double deliveryLongitude) {
        public OrderInput(String customer, UUID requestKey, List<OrderLine> lines) {
            this(customer, requestKey, lines, null, null, null, null);
        }
        public OrderInput(String customer, UUID requestKey, List<OrderLine> lines, String preferredWarehouseCode) {
            this(customer, requestKey, lines, preferredWarehouseCode, null, null, null);
        }
    }
    public record AllocationPreviewInput(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid OrderLine> lines,
                             @Pattern(regexp = WAREHOUSE_PATTERN) String preferredWarehouseCode,
                             AllocationStrategy allocationStrategy,
                             @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 2, fraction = 6) Double deliveryLatitude,
                             @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 6) Double deliveryLongitude) {}
    public record AllocationStrategyInput(@NotNull AllocationStrategy strategy) {}
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
}
