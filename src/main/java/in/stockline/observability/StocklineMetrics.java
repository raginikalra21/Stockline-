package in.stockline.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded-cardinality business and operational meters for dashboards and Actuator. */
@Component
public class StocklineMetrics {
    private static final Set<String> RESERVATION_FAILURES = Set.of(
        "INSUFFICIENT_STOCK", "BUSY", "DATABASE_UNAVAILABLE", "INVALID_INPUT", "FORBIDDEN", "OTHER");
    private final MeterRegistry registry;

    @Autowired
    public StocklineMetrics(MeterRegistry registry) { this.registry = registry; }

    /** No-op registry for isolated unit helpers and standalone simulation objects. */
    public StocklineMetrics() { this.registry = null; }

    public void reservationRejected(String reason) {
        increment("stockline.inventory.reservations", "outcome", "rejected", "reason",
            RESERVATION_FAILURES.contains(reason) ? reason : "OTHER");
    }

    public void reservationCreated(String strategy, int warehouseCount, long planningNanos) {
        increment("stockline.inventory.reservations", "outcome", "created", "reason", "none");
        increment("stockline.orders.created", "result", "created");
        increment("stockline.allocations.by_strategy", "strategy", safeStrategy(strategy), "outcome", "reserved");
        if (warehouseCount > 1) increment("stockline.orders.split", "result", "split");
        if (registry != null) {
            registry.summary("stockline.allocation.warehouses.per.order", "strategy", safeStrategy(strategy))
                .record(warehouseCount);
            registry.timer("stockline.allocation.planning", "strategy", safeStrategy(strategy), "stage", "reservation")
                .record(planningNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void allocationPreview(String strategy, long planningNanos, boolean complete) {
        if (registry == null) return;
        increment("stockline.allocations.by_strategy", "strategy", safeStrategy(strategy), "outcome", "previewed");
        registry.timer("stockline.allocation.planning", "strategy", safeStrategy(strategy), "stage", "preview")
            .record(planningNanos, TimeUnit.NANOSECONDS);
        if (!complete) increment("stockline.allocation.failures", "stage", "preview", "reason", "incomplete");
    }

    public void allocationFailure(String stage, String reason) {
        increment("stockline.allocation.failures", "stage", stage, "reason",
            Set.of("INSUFFICIENT_STOCK", "BUSY", "DATABASE_UNAVAILABLE", "INVALID_INPUT", "OTHER")
                .contains(reason) ? reason : "OTHER");
    }

    public void allocationFailedPlan(String strategy, long planningNanos) {
        if (registry != null) {
            registry.timer("stockline.allocation.planning", "strategy", safeStrategy(strategy), "stage", "reservation")
                .record(planningNanos, TimeUnit.NANOSECONDS);
        }
        increment("stockline.allocations.by_strategy", "strategy", safeStrategy(strategy), "outcome", "failed");
    }

    public void inventoryMutation(String operation) {
        String kind = switch (operation) {
            case "RESTOCK" -> "restock";
            case "TRANSFER" -> "transfer";
            case "STOCK_ADJUSTMENT" -> "adjustment";
            case "DAMAGED_STOCK" -> "damage";
            case "STOCK_WRITE_OFF" -> "writeoff";
            case "RETURN" -> "return";
            default -> "other";
        };
        increment("stockline.inventory.mutations", "operation", kind);
        if ("transfer".equals(kind)) increment("stockline.inventory.transfers", "result", "completed");
        if ("adjustment".equals(kind)) increment("stockline.inventory.adjustments", "result", "completed");
    }

    public void stockout() { increment("stockline.inventory.stockouts", "result", "available_to_zero"); }

    public void orderTransition(String outcome, Duration fulfillmentAge) {
        increment("stockline.orders.transitions", "outcome", outcome);
        if ("fulfilled".equals(outcome) && fulfillmentAge != null && !fulfillmentAge.isNegative()
                && registry != null) {
            registry.timer("stockline.orders.fulfillment.duration").record(fulfillmentAge);
        }
    }

    public void retryConflict() { increment("stockline.concurrency.conflicts", "kind", "optimistic_or_lock"); }
    public void retryAttempt() { increment("stockline.concurrency.retry_attempts", "result", "retried"); }
    public void retryExhausted() { increment("stockline.concurrency.failed_transactions", "reason", "retry_exhausted"); }
    public void failedTransaction() { increment("stockline.concurrency.failed_transactions", "reason", "transaction_error"); }
    public void lockWait(long nanos) {
        if (registry != null) registry.timer("stockline.database.warehouse_config_lock_wait")
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void eventCompleted(String eventType, long nanos) {
        increment("stockline.events.processed", "event_type", safeEvent(eventType));
        if (registry != null) registry.timer("stockline.events.processing.duration", "event_type", safeEvent(eventType))
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void eventRetried(String eventType) {
        increment("stockline.events.retried", "event_type", safeEvent(eventType));
    }

    public void eventDeadLettered(String eventType) {
        increment("stockline.events.dead_lettered", "event_type", safeEvent(eventType));
    }

    public void eventHandlerFailure(String eventType) {
        increment("stockline.events.handler_failures", "event_type", safeEvent(eventType));
    }

    public void leaseRecovered() { increment("stockline.events.lease_recoveries", "result", "recovered"); }
    public void workerBatchFailure() { increment("stockline.events.worker_batch_failures", "reason", "claim_or_completion"); }

    public void reconciliation(boolean valid) {
        if (!valid) increment("stockline.inventory.reconciliation.failures", "result", "invalid");
    }

    public void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private void increment(String name, String... tags) {
        if (registry != null) Counter.builder(name).tags(tags).register(registry).increment();
    }

    private String safeStrategy(String strategy) {
        return Set.of("SINGLE_WAREHOUSE", "NEAREST_WAREHOUSE", "FEWEST_SPLITS", "MAXIMUM_AVAILABILITY",
            "COST_OPTIMIZED", "LEGACY_PRIORITY").contains(strategy) ? strategy : "OTHER";
    }

    private String safeEvent(String eventType) {
        return Set.of("ORDER_RESERVED", "ORDER_FULFILLED", "ORDER_CANCELLED", "RESERVATION_EXPIRED",
            "INVENTORY_ADJUSTED", "STOCK_TRANSFERRED", "LOW_STOCK").contains(eventType) ? eventType : "OTHER";
    }
}
