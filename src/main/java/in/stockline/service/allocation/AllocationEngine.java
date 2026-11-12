package in.stockline.service.allocation;

import in.stockline.domain.model.AllocationModels.AllocationLineSummary;
import in.stockline.domain.model.AllocationModels.AllocationPreview;
import in.stockline.domain.model.AllocationModels.ExcludedWarehouse;
import in.stockline.domain.model.AllocationModels.ProposedAllocation;
import in.stockline.domain.model.AllocationModels.AllocationCandidate;
import in.stockline.domain.model.Models.AllocationStrategy;
import in.stockline.domain.model.Models.WarehouseStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure allocation planning over one database snapshot. It never writes inventory. */
@Service
public class AllocationEngine {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public AllocationPreview preview(Map<String, Integer> requested, AllocationStrategy strategy,
            List<AllocationCandidate> snapshot, String preferredWarehouse, Double destinationLatitude,
            Double destinationLongitude) {
        Map<String, Integer> demand = new TreeMap<>(requested);
        Map<String, Map<String, Integer>> assignments = new TreeMap<>();
        Map<String, Integer> capacityUsed = new HashMap<>();
        Map<String, AllocationCandidate> byKey = new HashMap<>();
        Map<String, List<AllocationCandidate>> bySku = new TreeMap<>();
        for (AllocationCandidate candidate : snapshot) {
            String key = key(candidate.stock().sku(), candidate.warehouse().code());
            byKey.put(key, candidate);
            bySku.computeIfAbsent(candidate.stock().sku(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<ExcludedWarehouse> excluded = new ArrayList<>();
        bySku.forEach((sku, candidates) -> candidates.forEach(candidate -> {
            var warehouse = candidate.warehouse();
            if (warehouse.status() != WarehouseStatus.ACTIVE)
                excluded.add(new ExcludedWarehouse(sku, warehouse.code(), warehouse.name(),
                    "Warehouse is " + warehouse.status() + " and cannot accept new allocations."));
            else if (candidate.stock().available() <= 0)
                excluded.add(new ExcludedWarehouse(sku, warehouse.code(), warehouse.name(),
                    "No available sellable stock is recorded at this warehouse."));
        }));

        switch (strategy) {
            case SINGLE_WAREHOUSE -> singleWarehouse(demand, bySku, assignments, capacityUsed, preferredWarehouse);
            case NEAREST_WAREHOUSE -> nearest(demand, bySku, assignments, capacityUsed, preferredWarehouse,
                destinationLatitude, destinationLongitude);
            case FEWEST_SPLITS -> fewestSplits(demand, bySku, assignments, capacityUsed, preferredWarehouse);
            case MAXIMUM_AVAILABILITY -> maximumAvailability(demand, bySku, assignments, capacityUsed, preferredWarehouse);
            case COST_OPTIMIZED -> costOptimized(demand, bySku, assignments, capacityUsed, preferredWarehouse);
        }

        List<AllocationLineSummary> lines = new ArrayList<>();
        demand.forEach((sku, quantity) -> {
            int assigned = assignments.getOrDefault(sku, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            lines.add(new AllocationLineSummary(sku, quantity, assigned, assigned == quantity));
        });
        boolean complete = lines.stream().allMatch(AllocationLineSummary::fullyFulfillable);
        int warehousesInvolved = (int) assignments.values().stream().flatMap(line -> line.keySet().stream()).distinct().count();

        List<ProposedAllocation> proposed = new ArrayList<>();
        BigDecimal totalCost = ZERO;
        Map<String, BigDecimal> usedWarehouseCosts = new HashMap<>();
        for (var line : assignments.entrySet()) {
            for (var entry : line.getValue().entrySet()) {
                AllocationCandidate candidate = byKey.get(key(line.getKey(), entry.getKey()));
                int quantity = entry.getValue();
                var warehouse = candidate.warehouse();
                BigDecimal shipping = warehouse.shippingCostPerUnit().multiply(BigDecimal.valueOf(quantity));
                BigDecimal handling = usedWarehouseCosts.putIfAbsent(warehouse.code(), warehouse.handlingCost()) == null
                    ? warehouse.handlingCost() : ZERO;
                BigDecimal cost = shipping.add(handling).setScale(2, RoundingMode.HALF_UP);
                totalCost = totalCost.add(cost);
                BigDecimal distance = estimatedDistance(warehouse.latitude(), warehouse.longitude(),
                    destinationLatitude, destinationLongitude);
                proposed.add(new ProposedAllocation(line.getKey(), warehouse.code(), warehouse.name(),
                    demand.get(line.getKey()), quantity, candidate.stock().available(), cost, distance,
                    selectionReason(strategy, candidate, preferredWarehouse, distance)));
            }
        }
        proposed.sort(Comparator.comparing(ProposedAllocation::sku).thenComparing(ProposedAllocation::warehouseCode));
        String explanation = switch (strategy) {
            case SINGLE_WAREHOUSE -> complete
                ? "One active warehouse supplies every line within its configured per-order capacity."
                : "No single active warehouse can supply every requested line and quantity.";
            case NEAREST_WAREHOUSE -> destinationLatitude != null && destinationLongitude != null
                ? "A warehouse that can fulfill the complete order is preferred; otherwise stock is split by estimated straight-line distance. This is not a routed delivery distance."
                : "A warehouse that can fulfill the complete order is preferred; otherwise stock is split by preferred location, warehouse priority, then code.";
            case FEWEST_SPLITS -> "Warehouses are selected by their total contribution to requested units, reducing warehouse splits; ties use preferred location, priority, then code.";
            case MAXIMUM_AVAILABILITY -> "A single warehouse with enough stock is preferred; otherwise warehouses with the most eligible available units are used first.";
            case COST_OPTIMIZED -> "The plan minimizes estimated handling and per-unit shipping cost using configured warehouse rates; estimates exclude carrier tariffs and taxes.";
        };
        if (!complete) explanation += " The preview is partial; no inventory has been reserved.";
        return new AllocationPreview(strategy, complete, warehousesInvolved,
            totalCost.setScale(2, RoundingMode.HALF_UP), List.copyOf(lines), List.copyOf(proposed),
            List.copyOf(excluded), explanation);
    }

    private void singleWarehouse(Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku,
            Map<String, Map<String, Integer>> assignments, Map<String, Integer> used, String preferred) {
        List<AllocationCandidate> warehouses = bySku.values().stream().flatMap(List::stream)
            .collect(java.util.stream.Collectors.toMap(c -> c.warehouse().code(), c -> c, (a, b) -> a,
                LinkedHashMap::new)).values().stream().toList();
        List<AllocationCandidate> complete = warehouses.stream().filter(first -> canCoverAll(first.warehouse().code(), demand, bySku))
            .sorted(Comparator.comparing((AllocationCandidate c) -> !c.warehouse().code().equals(preferred))
                .thenComparingInt(c -> c.warehouse().priority()).thenComparing(c -> c.warehouse().code())).toList();
        if (!complete.isEmpty()) {
            String code = complete.get(0).warehouse().code();
            demand.forEach((sku, quantity) -> put(assignments, sku, code, quantity));
            used.put(code, demand.values().stream().mapToInt(Integer::intValue).sum());
            return;
        }
        AllocationCandidate best = warehouses.stream().filter(c -> c.warehouse().status() == WarehouseStatus.ACTIVE)
            .max(Comparator.<AllocationCandidate>comparingLong(c -> coverage(c.warehouse().code(), demand, bySku))
                .thenComparingInt(c -> -c.warehouse().priority()).thenComparing(c -> c.warehouse().code(), Comparator.reverseOrder()))
            .orElse(null);
        if (best != null) {
            for (var line : demand.entrySet()) {
                AllocationCandidate row = candidate(bySku, line.getKey(), best.warehouse().code());
                if (row == null) continue;
                int amount = (int) Math.min(line.getValue(), row.stock().available());
                amount = capacityBound(best.warehouse(), used, amount);
                if (amount > 0) { put(assignments, line.getKey(), best.warehouse().code(), amount); used.merge(best.warehouse().code(), amount, Integer::sum); }
            }
        }
    }

    private boolean canCoverAll(String warehouse, Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku) {
        AllocationCandidate first = null;
        int units = 0;
        for (var line : demand.entrySet()) {
            AllocationCandidate row = candidate(bySku, line.getKey(), warehouse);
            if (row == null || row.warehouse().status() != WarehouseStatus.ACTIVE || row.stock().available() < line.getValue()) return false;
            if (first == null) first = row;
            units += line.getValue();
        }
        return first != null && (first.warehouse().maxFulfillmentCapacity() == null || units <= first.warehouse().maxFulfillmentCapacity());
    }

    private long coverage(String warehouse, Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku) {
        long total = 0;
        for (var line : demand.entrySet()) {
            AllocationCandidate row = candidate(bySku, line.getKey(), warehouse);
            if (row != null && row.warehouse().status() == WarehouseStatus.ACTIVE)
                total += Math.min(line.getValue(), row.stock().available());
        }
        AllocationCandidate any = bySku.values().stream().flatMap(List::stream)
            .filter(c -> c.warehouse().code().equals(warehouse)).findFirst().orElse(null);
        if (any != null && any.warehouse().maxFulfillmentCapacity() != null)
            total = Math.min(total, any.warehouse().maxFulfillmentCapacity());
        return total;
    }

    private void nearest(Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku,
            Map<String, Map<String, Integer>> assignments, Map<String, Integer> used, String preferred,
            Double latitude, Double longitude) {
        List<AllocationCandidate> warehouses = uniqueWarehouses(bySku);
        warehouses.sort(Comparator.comparing((AllocationCandidate c) -> estimatedDistance(c.warehouse().latitude(),
                c.warehouse().longitude(), latitude, longitude), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> !c.warehouse().code().equals(preferred))
            .thenComparingInt(c -> c.warehouse().priority()).thenComparing(c -> c.warehouse().code()));
        AllocationCandidate wholeOrder = warehouses.stream().filter(candidate -> canCoverAll(
            candidate.warehouse().code(), demand, bySku)).findFirst().orElse(null);
        if (wholeOrder != null) {
            demand.forEach((sku, quantity) -> put(assignments, sku, wholeOrder.warehouse().code(), quantity));
            used.put(wholeOrder.warehouse().code(), demand.values().stream().mapToInt(Integer::intValue).sum());
            return;
        }
        allocateByWarehouseOrder(demand, bySku, warehouses, assignments, used, false);
    }

    private void fewestSplits(Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku,
            Map<String, Map<String, Integer>> assignments, Map<String, Integer> used, String preferred) {
        List<AllocationCandidate> warehouses = uniqueWarehouses(bySku);
        warehouses.sort(Comparator.comparingLong((AllocationCandidate c) -> coverage(c.warehouse().code(), demand, bySku)).reversed()
            .thenComparing(c -> !c.warehouse().code().equals(preferred))
            .thenComparingInt(c -> c.warehouse().priority()).thenComparing(c -> c.warehouse().code()));
        allocateByWarehouseOrder(demand, bySku, warehouses, assignments, used, true);
    }

    private void allocateByWarehouseOrder(Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku,
            List<AllocationCandidate> warehouses, Map<String, Map<String, Integer>> assignments,
            Map<String, Integer> used, boolean scarcityFirst) {
        List<String> lineOrder = new ArrayList<>(demand.keySet());
        if (scarcityFirst) lineOrder.sort(Comparator.comparingInt((String sku) -> (int) bySku.getOrDefault(sku, List.of()).stream()
            .filter(c -> c.warehouse().status() == WarehouseStatus.ACTIVE && c.stock().available() > 0).count()).thenComparing(sku -> sku));
        for (AllocationCandidate warehouseRow : warehouses) {
            String code = warehouseRow.warehouse().code();
            if (warehouseRow.warehouse().status() != WarehouseStatus.ACTIVE) continue;
            for (String sku : lineOrder) {
                int remaining = demand.get(sku) - assigned(assignments, sku);
                if (remaining <= 0) continue;
                AllocationCandidate row = candidate(bySku, sku, code);
                if (row == null) continue;
                int amount = (int) Math.min(remaining, row.stock().available());
                amount = capacityBound(row.warehouse(), used, amount);
                if (amount > 0) { put(assignments, sku, code, amount); used.merge(code, amount, Integer::sum); }
            }
        }
    }

    private void maximumAvailability(Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku,
            Map<String, Map<String, Integer>> assignments, Map<String, Integer> used, String preferred) {
        for (var line : demand.entrySet()) {
            List<AllocationCandidate> rows = bySku.getOrDefault(line.getKey(), List.of()).stream()
                .filter(c -> c.warehouse().status() == WarehouseStatus.ACTIVE && c.stock().available() > 0)
                .sorted(Comparator.comparing((AllocationCandidate c) -> !(c.stock().available() >= line.getValue()
                        && capacityLeft(c.warehouse(), used) >= line.getValue()))
                    .thenComparing(Comparator.comparingLong((AllocationCandidate c) -> c.stock().available()).reversed())
                    .thenComparing(c -> !c.warehouse().code().equals(preferred))
                    .thenComparingInt(c -> c.warehouse().priority()).thenComparing(c -> c.warehouse().code())).toList();
            allocateLine(line.getKey(), line.getValue(), rows, assignments, used);
        }
    }

    private void costOptimized(Map<String, Integer> demand, Map<String, List<AllocationCandidate>> bySku,
            Map<String, Map<String, Integer>> assignments, Map<String, Integer> used, String preferred) {
        for (var line : demand.entrySet()) {
            int quantity = line.getValue();
            List<AllocationCandidate> rows = bySku.getOrDefault(line.getKey(), List.of()).stream()
                .filter(c -> c.warehouse().status() == WarehouseStatus.ACTIVE && c.stock().available() > 0)
                .sorted(Comparator.comparing((AllocationCandidate c) -> marginalCost(c, quantity))
                    .thenComparing(c -> !c.warehouse().code().equals(preferred))
                    .thenComparingInt(c -> c.warehouse().priority()).thenComparing(c -> c.warehouse().code())).toList();
            AllocationCandidate single = rows.stream().filter(c -> c.stock().available() >= quantity
                && capacityLeft(c.warehouse(), used) >= quantity)
                .min(Comparator.comparing((AllocationCandidate c) -> totalCost(c, quantity))
                    .thenComparingInt(c -> c.warehouse().priority()).thenComparing(c -> c.warehouse().code())).orElse(null);
            if (single != null) rows = List.of(single);
            allocateLine(line.getKey(), quantity, rows, assignments, used);
        }
    }

    private void allocateLine(String sku, int quantity, List<AllocationCandidate> candidates,
            Map<String, Map<String, Integer>> assignments, Map<String, Integer> used) {
        int remaining = quantity;
        for (AllocationCandidate candidate : candidates) {
            if (remaining <= 0) break;
            int amount = (int) Math.min(remaining, candidate.stock().available());
            amount = capacityBound(candidate.warehouse(), used, amount);
            if (amount > 0) {
                put(assignments, sku, candidate.warehouse().code(), amount);
                used.merge(candidate.warehouse().code(), amount, Integer::sum);
                remaining -= amount;
            }
        }
    }

    private List<AllocationCandidate> uniqueWarehouses(Map<String, List<AllocationCandidate>> bySku) {
        return bySku.values().stream().flatMap(List::stream)
            .collect(java.util.stream.Collectors.toMap(c -> c.warehouse().code(), c -> c, (a, b) -> a, LinkedHashMap::new))
            .values().stream().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
    private AllocationCandidate candidate(Map<String, List<AllocationCandidate>> bySku, String sku, String warehouse) {
        return bySku.getOrDefault(sku, List.of()).stream().filter(c -> c.warehouse().code().equals(warehouse)).findFirst().orElse(null);
    }
    private int capacityLeft(in.stockline.domain.model.Models.Warehouse warehouse, Map<String, Integer> used) {
        return warehouse.maxFulfillmentCapacity() == null ? Integer.MAX_VALUE
            : Math.max(0, warehouse.maxFulfillmentCapacity() - used.getOrDefault(warehouse.code(), 0));
    }
    private int capacityBound(in.stockline.domain.model.Models.Warehouse warehouse, Map<String, Integer> used, int desired) {
        return Math.min(desired, capacityLeft(warehouse, used));
    }
    private int assigned(Map<String, Map<String, Integer>> assignments, String sku) {
        return assignments.getOrDefault(sku, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
    }
    private void put(Map<String, Map<String, Integer>> assignments, String sku, String warehouse, int quantity) {
        assignments.computeIfAbsent(sku, ignored -> new TreeMap<>()).merge(warehouse, quantity, Integer::sum);
    }
    private BigDecimal totalCost(AllocationCandidate c, int quantity) {
        return c.warehouse().handlingCost().add(c.warehouse().shippingCostPerUnit().multiply(BigDecimal.valueOf(quantity)));
    }
    private BigDecimal marginalCost(AllocationCandidate c, int quantity) {
        int contribution = Math.max(1, Math.min(quantity, (int) Math.min(Integer.MAX_VALUE, c.stock().available())));
        return c.warehouse().shippingCostPerUnit().add(c.warehouse().handlingCost()
            .divide(BigDecimal.valueOf(contribution), 6, RoundingMode.HALF_UP));
    }
    private BigDecimal estimatedDistance(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null;
        double earthRadius = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        a = Math.max(0, Math.min(1, a));
        double km = earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(km).setScale(3, RoundingMode.HALF_UP);
    }
    private String selectionReason(AllocationStrategy strategy, AllocationCandidate c, String preferred, BigDecimal distance) {
        return switch (strategy) {
            case SINGLE_WAREHOUSE -> "This active warehouse alone covers the complete order.";
            case NEAREST_WAREHOUSE -> distance == null ? "Selected using deterministic warehouse priority because coordinate distance is unavailable."
                : "Selected by estimated straight-line distance of " + distance + " km.";
            case FEWEST_SPLITS -> "Selected for its high contribution to requested units to reduce warehouse splits.";
            case MAXIMUM_AVAILABILITY -> "Selected for its available stock; single-warehouse coverage is preferred when possible.";
            case COST_OPTIMIZED -> "Selected using configured handling and per-unit shipping estimates.";
        };
    }
    private String key(String sku, String warehouse) { return sku + "\u0000" + warehouse; }
}
