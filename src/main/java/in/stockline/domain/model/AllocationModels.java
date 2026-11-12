package in.stockline.domain.model;

import in.stockline.domain.model.Models.AllocationStrategy;
import in.stockline.domain.model.Models.Stock;
import in.stockline.domain.model.Models.Warehouse;

import java.math.BigDecimal;
import java.util.List;

public final class AllocationModels {
    private AllocationModels() {}

    public record AllocationCandidate(Stock stock, Warehouse warehouse) {}

    public record AllocationLineSummary(String sku, int requestedQuantity, int proposedQuantity,
                                        boolean fullyFulfillable) {}
    public record ProposedAllocation(String sku, String warehouseCode, String warehouse,
                                     int requestedQuantity, int proposedQuantity, long availableBefore,
                                     BigDecimal estimatedCost, BigDecimal estimatedDistanceKm, String reason) {}
    public record ExcludedWarehouse(String sku, String warehouseCode, String warehouse, String reason) {}
    public record AllocationPreview(AllocationStrategy strategy, boolean fullyFulfillable,
                                   int warehousesInvolved, BigDecimal estimatedTotalCost,
                                   List<AllocationLineSummary> lines, List<ProposedAllocation> allocations,
                                   List<ExcludedWarehouse> excludedWarehouses, String explanation) {}
    public record AllocationStrategyConfiguration(AllocationStrategy defaultStrategy,
                                                  List<AllocationStrategy> supportedStrategies) {}
}
