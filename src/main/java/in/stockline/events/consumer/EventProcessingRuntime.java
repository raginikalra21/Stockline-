package in.stockline.events.consumer;

import in.stockline.domain.model.Models.WorkerHealth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EventProcessingRuntime {
    private final String instanceId = UUID.randomUUID().toString();
    private final int workers;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicReference<OffsetDateTime> lastPoll = new AtomicReference<>();
    private final AtomicReference<OffsetDateTime> lastCompleted = new AtomicReference<>();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private volatile int queuedTasks;

    public EventProcessingRuntime(@Value("${stockline.events.worker-count:4}") int workers) { this.workers = workers; }
    public String workerId() { return instanceId; }
    public void polled() { lastPoll.set(now()); }
    public void queued(int count) { queuedTasks = count; }
    public void started() { active.incrementAndGet(); }
    public void stopped() { active.decrementAndGet(); }
    public void completed() { lastCompleted.set(now()); }
    public void failed(String reason) { lastFailure.set(reason); }
    public WorkerHealth snapshot() { return new WorkerHealth(instanceId, workers, active.get(), queuedTasks,
        lastPoll.get(), lastCompleted.get(), lastFailure.get()); }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
