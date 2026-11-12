package in.stockline.events.consumer;

import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("eventOutbox")
public class EventOutboxHealthIndicator implements HealthIndicator {
    private final Store store;
    private final EventProcessingRuntime runtime;
    public EventOutboxHealthIndicator(Store store, EventProcessingRuntime runtime) {
        this.store = store; this.runtime = runtime;
    }
    @Override public Health health() {
        var metrics = store.eventMetrics(InventoryService.now());
        var worker = runtime.snapshot();
        Health.Builder result = metrics.dead() > 0 ? Health.status("DEGRADED") : Health.up();
        return result.withDetail("pending", metrics.pending()).withDetail("processing", metrics.processing())
            .withDetail("retryWait", metrics.retryWait()).withDetail("dead", metrics.dead())
            .withDetail("oldestPendingAgeMillis", metrics.oldestPendingAgeMillis())
            .withDetail("activeWorkers", worker.activeWorkers()).withDetail("configuredWorkers", worker.configuredWorkers())
            .withDetail("queuedTasks", worker.queuedTasks()).withDetail("lastPollAt", worker.lastPollAt())
            .withDetail("lastCompletedAt", worker.lastCompletedAt())
            .build();
    }
}
