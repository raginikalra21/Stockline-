package in.stockline.service.audit;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import in.stockline.persistence.repository.Store;
import static in.stockline.domain.model.Models.*;

@Service
public class InventoryAuditService {
    private final Store store;

    public InventoryAuditService(Store store) {
        this.store = store;
    }

    private record BucketKey(String sku, String warehouseCode, StockBucket bucket) {}
    private record OperationBucket(UUID operationId, String sku, String warehouseCode) {}

    private static final class BucketAudit {
        long ledgerTotal;
        Long lastBalance;
        int invalidMovements;
        int discontinuities;
        int missingOperations;
        int unbalancedTransfers;
        int unbalancedOperations;
        boolean balanceMismatch;
        String chainStatus = "VALID";
    }

    public List<InventoryMovement> movements(String search, String warehouseCode, MovementType type,
                                      OffsetDateTime from, OffsetDateTime to, UUID orderId,
                                      int limit, int offset) {
        return store.movements(search, warehouseCode, type, from, to, orderId, limit, offset);
    }

    public List<InventoryMovement> movements(String search, String warehouseCode, MovementType type,
                                      OffsetDateTime from, OffsetDateTime to, UUID orderId,
                                      int limit, int offset, Set<String> warehouseScope) {
        return store.movements(search, warehouseCode, type, from, to, orderId, limit, offset, warehouseScope);
    }

    public List<LowStockAlert> alerts() {
        return store.lowStockAlerts();
    }

    public List<ReconciliationLine> reconcile() {
        List<Stock> stocks = store.inventory();
        List<InventoryMovement> movements = store.allMovementsForIntegrity();
        Map<UUID, String> operationTypes = store.operationTypes();
        Set<UUID> missingOperationMovements = store.movementsWithMissingOperations();
        Map<String, String> names = store.productWarehouseNames();
        Map<BucketKey, BucketAudit> audits = new HashMap<>();
        Map<BucketKey, List<InventoryMovement>> chains = new HashMap<>();
        Map<UUID, List<InventoryMovement>> operations = new HashMap<>();

        for (Stock stock : stocks) {
            for (StockBucket bucket : StockBucket.values()) {
                audits.put(new BucketKey(stock.sku(), stock.warehouseCode(), bucket), new BucketAudit());
            }
        }

        // Store orders rows by product, warehouse, bucket, timestamp, then movement ID.
        // Keep that ordering here so equal timestamps always produce the same chain.
        for (InventoryMovement movement : movements) {
            BucketKey key = key(movement);
            BucketAudit audit = audits.computeIfAbsent(key, ignored -> new BucketAudit());
            chains.computeIfAbsent(key, ignored -> new ArrayList<>()).add(movement);
            operations.computeIfAbsent(movement.operationId(), ignored -> new ArrayList<>()).add(movement);
            if (missingOperationMovements.contains(movement.movementId())) audit.missingOperations++;
        }

        for (var entry : chains.entrySet()) verifyChain(entry.getKey(), entry.getValue(), audits.get(entry.getKey()));

        for (var entry : operations.entrySet()) {
            String operationType = operationTypes.get(entry.getKey());
            if (operationType == null) continue;
            verifyOperation(operationType, entry.getValue(), audits);
        }

        Map<BucketKey, Long> current = new HashMap<>();
        for (Stock stock : stocks) {
            for (StockBucket bucket : StockBucket.values()) {
                current.put(new BucketKey(stock.sku(), stock.warehouseCode(), bucket), actual(stock, bucket));
            }
        }

        boolean globalIntegrity = false;
        for (var entry : audits.entrySet()) {
            BucketAudit audit = entry.getValue();
            long actual = current.getOrDefault(entry.getKey(), 0L);
            long last = audit.lastBalance == null ? 0 : audit.lastBalance;
            audit.balanceMismatch = audit.ledgerTotal != actual || last != actual;
            if (audit.invalidMovements > 0) audit.chainStatus = "INVALID_MOVEMENT";
            else if (audit.discontinuities > 0) audit.chainStatus = "DISCONTINUITY";
            else if (audit.balanceMismatch) audit.chainStatus = "BALANCE_MISMATCH";
            globalIntegrity |= audit.invalidMovements > 0 || audit.discontinuities > 0 || audit.missingOperations > 0
                || audit.unbalancedTransfers > 0 || audit.unbalancedOperations > 0 || audit.balanceMismatch;
        }
        String overallStatus = globalIntegrity ? "INVALID" : "VALID";

        Set<BucketKey> resultKeys = new HashSet<>(audits.keySet());
        resultKeys.addAll(current.keySet());
        List<BucketKey> orderedKeys = resultKeys.stream()
            .sorted(Comparator.comparing(BucketKey::sku).thenComparing(BucketKey::warehouseCode)
                .thenComparing(BucketKey::bucket))
            .toList();
        List<ReconciliationLine> result = new ArrayList<>();
        for (BucketKey bucketKey : orderedKeys) {
            String namesKey = bucketKey.sku() + "|" + bucketKey.warehouseCode();
            String[] labels = names.getOrDefault(namesKey, namesKey).split("\\|", 2);
            BucketAudit audit = audits.get(bucketKey);
            long expected = audit.ledgerTotal;
            long actual = current.getOrDefault(bucketKey, 0L);
            long difference = difference(actual, expected);
            result.add(new ReconciliationLine(bucketKey.sku(), labels[0], bucketKey.warehouseCode(), labels[1],
                bucketKey.bucket(), expected, actual, difference, difference == 0 ? "MATCH" : "DISCREPANCY",
                audit.chainStatus, audit.invalidMovements, audit.discontinuities, audit.missingOperations,
                audit.unbalancedTransfers, audit.unbalancedOperations, audit.balanceMismatch, overallStatus));
        }
        return result;
    }

    private void verifyChain(BucketKey key, List<InventoryMovement> chain, BucketAudit audit) {
        chain.sort(Comparator.comparing(InventoryMovement::timestamp).thenComparing(InventoryMovement::movementId));
        InventoryMovement previous = null;
        long total = 0;
        for (InventoryMovement movement : chain) {
            if (!validArithmetic(movement)) audit.invalidMovements++;
            if (previous == null) {
                if (movement.previousStock() != 0) audit.discontinuities++;
            } else if (movement.previousStock() != previous.newStock()) {
                audit.discontinuities++;
            }
            try {
                total = Math.addExact(total, movement.quantityChange());
            } catch (ArithmeticException overflow) {
                audit.invalidMovements++;
            }
            previous = movement;
        }
        audit.ledgerTotal = total;
        if (previous != null) audit.lastBalance = previous.newStock();
    }

    private boolean validArithmetic(InventoryMovement movement) {
        if (movement.quantityChange() == 0 || movement.previousStock() < 0 || movement.newStock() < 0) return false;
        try {
            return Math.addExact(movement.previousStock(), movement.quantityChange()) == movement.newStock();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private void verifyOperation(String operationType, List<InventoryMovement> movements,
                                 Map<BucketKey, BucketAudit> audits) {
        switch (operationType) {
            case "TRANSFER" -> verifyTransfer(movements, audits);
            case "ORDER_RESERVATION" -> verifyPairedOperation(movements, MovementType.RESERVATION,
                StockBucket.AVAILABLE, StockBucket.RESERVED, true, audits);
            case "RESERVATION_RELEASE" -> verifyPairedOperation(movements, MovementType.RESERVATION_RELEASE,
                StockBucket.RESERVED, StockBucket.AVAILABLE, true, audits);
            case "FULFILLMENT" -> verifyPairedOperation(movements, MovementType.FULFILLMENT,
                StockBucket.RESERVED, StockBucket.SHIPPED, true, audits);
            case "RETURN" -> verifyPairedOperation(movements, MovementType.RETURN,
                StockBucket.SHIPPED, StockBucket.AVAILABLE, true, audits);
            case "DAMAGED_STOCK" -> verifyPairedOperation(movements, MovementType.DAMAGED_STOCK,
                StockBucket.AVAILABLE, StockBucket.DAMAGED, false, audits);
            case "STOCK_WRITE_OFF" -> verifyPairedOperation(movements, MovementType.STOCK_WRITE_OFF,
                StockBucket.DAMAGED, StockBucket.WRITTEN_OFF, false, audits);
            case "RESTOCK" -> verifySingle(movements, MovementType.RESTOCK, StockBucket.AVAILABLE, audits);
            case "STOCK_ADJUSTMENT" -> verifyAdjustment(movements, audits);
            case "INITIAL_STOCK" -> verifyOpeningBalance(movements, audits);
            default -> markOperationInvalid(movements, audits, false);
        }
    }

    private void verifyPairedOperation(List<InventoryMovement> movements, MovementType expectedType,
                                       StockBucket negativeBucket, StockBucket positiveBucket,
                                       boolean orderBound,
                                       Map<BucketKey, BucketAudit> audits) {
        Map<OperationBucket, List<InventoryMovement>> groups = byProductWarehouse(movements);
        boolean valid = !groups.isEmpty();
        for (List<InventoryMovement> pair : groups.values()) {
            List<InventoryMovement> negative = pair.stream().filter(m -> m.movementType() == expectedType
                && m.stockBucket() == negativeBucket && m.quantityChange() < 0).toList();
            List<InventoryMovement> positive = pair.stream().filter(m -> m.movementType() == expectedType
                && m.stockBucket() == positiveBucket && m.quantityChange() > 0).toList();
            valid &= pair.size() == 2 && negative.size() == 1 && positive.size() == 1
                && negative.get(0).quantityChange() == -positive.get(0).quantityChange()
                && java.util.Objects.equals(negative.get(0).referenceOrderId(), positive.get(0).referenceOrderId())
                && (!orderBound || negative.get(0).referenceOrderId() != null);
        }
        if (!valid) markOperationInvalid(movements, audits, false);
    }

    private void verifyTransfer(List<InventoryMovement> movements, Map<BucketKey, BucketAudit> audits) {
        List<InventoryMovement> outs = movements.stream().filter(m -> m.movementType() == MovementType.TRANSFER_OUT).toList();
        List<InventoryMovement> ins = movements.stream().filter(m -> m.movementType() == MovementType.TRANSFER_IN).toList();
        boolean[] matched = new boolean[ins.size()];
        boolean valid = !outs.isEmpty() && outs.size() == ins.size() && movements.size() == outs.size() + ins.size();
        for (InventoryMovement out : outs) {
            int match = -1;
            for (int i = 0; i < ins.size(); i++) {
                InventoryMovement in = ins.get(i);
                if (!matched[i] && out.productId().equals(in.productId()) && out.quantityChange() < 0
                    && in.quantityChange() > 0 && out.quantityChange() == -in.quantityChange()
                    && out.stockBucket() == StockBucket.AVAILABLE && in.stockBucket() == StockBucket.AVAILABLE
                    && out.sourceWarehouse() != null && out.destinationWarehouse() != null
                    && out.sourceWarehouse().equals(out.warehouseId())
                    && in.warehouseId().equals(out.destinationWarehouse())
                    && out.sourceWarehouse().equals(in.sourceWarehouse())
                    && out.destinationWarehouse().equals(in.destinationWarehouse())) {
                    match = i;
                    break;
                }
            }
            if (match < 0) valid = false;
            else matched[match] = true;
        }
        for (boolean found : matched) valid &= found;
        if (!valid) markOperationInvalid(movements, audits, true);
    }

    private void verifySingle(List<InventoryMovement> movements, MovementType expectedType,
                              StockBucket expectedBucket, Map<BucketKey, BucketAudit> audits) {
        boolean valid = movements.size() == 1 && movements.get(0).movementType() == expectedType
            && movements.get(0).stockBucket() == expectedBucket && movements.get(0).quantityChange() > 0;
        if (!valid) markOperationInvalid(movements, audits, false);
    }

    private void verifyAdjustment(List<InventoryMovement> movements, Map<BucketKey, BucketAudit> audits) {
        boolean valid = !movements.isEmpty() && movements.stream().allMatch(m ->
            m.movementType() == MovementType.STOCK_ADJUSTMENT && m.stockBucket() != StockBucket.RESERVED);
        if (!valid) markOperationInvalid(movements, audits, false);
    }

    private void verifyOpeningBalance(List<InventoryMovement> movements, Map<BucketKey, BucketAudit> audits) {
        boolean valid = !movements.isEmpty() && movements.stream().allMatch(m ->
            m.movementType() == MovementType.INITIAL_STOCK && m.previousStock() == 0 && m.quantityChange() > 0);
        if (!valid) markOperationInvalid(movements, audits, false);
    }

    private Map<OperationBucket, List<InventoryMovement>> byProductWarehouse(List<InventoryMovement> movements) {
        Map<OperationBucket, List<InventoryMovement>> groups = new HashMap<>();
        for (InventoryMovement movement : movements) {
            groups.computeIfAbsent(new OperationBucket(movement.operationId(), movement.productId(), movement.warehouseId()),
                ignored -> new ArrayList<>()).add(movement);
        }
        return groups;
    }

    private void markOperationInvalid(List<InventoryMovement> movements, Map<BucketKey, BucketAudit> audits,
                                      boolean transfer) {
        Set<BucketKey> affected = new HashSet<>();
        for (InventoryMovement movement : movements) affected.add(key(movement));
        for (BucketKey key : affected) {
            BucketAudit audit = audits.computeIfAbsent(key, ignored -> new BucketAudit());
            if (transfer) audit.unbalancedTransfers++;
            else audit.unbalancedOperations++;
        }
    }

    private BucketKey key(InventoryMovement movement) {
        return new BucketKey(movement.productId(), movement.warehouseId(), movement.stockBucket());
    }

    private long difference(long actual, long expected) {
        try {
            return Math.subtractExact(actual, expected);
        } catch (ArithmeticException overflow) {
            return actual >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private long actual(Stock stock, StockBucket bucket) {
        return switch (bucket) {
            case AVAILABLE -> stock.available();
            case RESERVED -> stock.reserved();
            case DAMAGED -> stock.damaged();
            case SHIPPED -> stock.shipped();
            case WRITTEN_OFF -> stock.writtenOff();
        };
    }
}
