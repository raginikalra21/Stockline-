package in.stockline.events.consumer;

import in.stockline.domain.model.Models.EventType;
import in.stockline.events.handler.InventoryAdjustedHandler;
import in.stockline.events.handler.LowStockHandler;
import in.stockline.events.handler.OrderCancelledHandler;
import in.stockline.events.handler.OrderFulfilledHandler;
import in.stockline.events.handler.OrderReservedHandler;
import in.stockline.events.handler.ReservationExpiredHandler;
import in.stockline.events.handler.StockTransferredHandler;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import in.stockline.observability.StocklineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

@Component
public class FulfillmentWorker implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(FulfillmentWorker.class);
    private final Store store;
    private final EventProcessor processor;
    private final EventProcessingRuntime runtime;
    private final ThreadPoolTaskExecutor pool;
    private final int workerCount;
    private final int batchSize;
    private final Duration lease;
    private final StocklineMetrics metrics;

    @Autowired
    public FulfillmentWorker(Store store, EventProcessor processor, EventProcessingRuntime runtime,
            @Value("${stockline.events.worker-count:4}") int workerCount,
            @Value("${stockline.events.batch-size:16}") int batchSize,
            @Value("${stockline.events.lease:45s}") Duration lease,
            @Value("${stockline.events.queue-capacity:8}") int queueCapacity,
            StocklineMetrics metrics) {
        if (workerCount < 1 || workerCount > 64 || batchSize < 1 || batchSize > 100 || queueCapacity < 1)
            throw new IllegalArgumentException("Event worker and queue settings are outside supported bounds.");
        this.store = store; this.processor = processor; this.runtime = runtime; this.workerCount = workerCount;
        this.batchSize = batchSize; this.lease = lease; this.metrics = metrics;
        this.pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(workerCount);
        pool.setMaxPoolSize(workerCount);
        pool.setQueueCapacity(queueCapacity);
        pool.setThreadNamePrefix("stockline-event-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
    }

    /** Synchronous one-batch entrypoint retained for tests and the isolated concurrency lab. */
    public FulfillmentWorker(Store store, InventoryService inventory) {
        this(store, demoProcessor(store, inventory), new EventProcessingRuntime(1), 1, 100,
            Duration.ofSeconds(45), 1, new StocklineMetrics());
    }

    private static EventProcessor demoProcessor(Store store, InventoryService inventory) {
        var runtime = new EventProcessingRuntime(1);
        return new EventProcessor(store, List.of(new OrderReservedHandler(inventory),
            new OrderFulfilledHandler(inventory), new OrderCancelledHandler(inventory),
            new LowStockHandler(store), new InventoryAdjustedHandler(store),
            new StockTransferredHandler(store), new ReservationExpiredHandler(inventory)), runtime,
            Duration.ofSeconds(1), Duration.ofMinutes(5), 8, Duration.ofSeconds(30));
    }

    @Scheduled(initialDelayString = "${stockline.events.initial-delay-ms:0}",
        fixedDelayString = "${stockline.events.poll-interval-ms:500}")
    public void dispatchAvailableWork() {
        runtime.polled();
        int active = pool.getActiveCount();
        int queued = pool.getThreadPoolExecutor().getQueue().size();
        runtime.queued(queued);
        int slots = Math.max(0, workerCount - active - queued);
        for (int index = 0; index < slots; index++) {
            try { pool.execute(this::processDueOrders); }
            catch (RejectedExecutionException rejected) {
                log.debug("Event worker queue is full; remaining work will be claimed on the next poll.");
                break;
            }
        }
    }

    public void processDueOrders() {
        runtime.started();
        try {
            String workerId = runtime.workerId() + ":" + Thread.currentThread().getName();
            var events = store.claimEvents(workerId, batchSize, InventoryService.now(), lease);
            for (var event : events) processor.process(event, workerId);
            runtime.queued(pool.getThreadPoolExecutor().getQueue().size());
        } catch (RuntimeException failure) {
            runtime.failed(failure.getClass().getSimpleName());
            metrics.workerBatchFailure();
            log.warn("Event worker batch could not be claimed or completed: {}", failure.getClass().getSimpleName());
        } finally { runtime.stopped(); }
    }

    @Override public void destroy() { pool.shutdown(); }
}
