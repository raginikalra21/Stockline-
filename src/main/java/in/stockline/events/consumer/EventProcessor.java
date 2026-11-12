package in.stockline.events.consumer;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.events.handler.OutboxEventHandler;
import in.stockline.events.handler.PermanentEventException;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import in.stockline.observability.CorrelationContext;
import in.stockline.observability.StocklineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class EventProcessor implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);
    private final Store store;
    private final Map<EventType, OutboxEventHandler> handlers;
    private final EventProcessingRuntime runtime;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration processingTimeout;
    private final int maxAttempts;
    private final ThreadPoolExecutor handlerPool;
    private final StocklineMetrics metrics;

    @Autowired
    public EventProcessor(Store store, List<OutboxEventHandler> handlers, EventProcessingRuntime runtime,
            @Value("${stockline.events.retry.base-delay:1s}") Duration baseDelay,
            @Value("${stockline.events.retry.max-delay:5m}") Duration maxDelay,
            @Value("${stockline.events.retry.max-attempts:8}") int maxAttempts,
            @Value("${stockline.events.processing-timeout:30s}") Duration processingTimeout,
            @Value("${stockline.events.worker-count:4}") int handlerConcurrency,
            StocklineMetrics metrics) {
        this.store = store; this.runtime = runtime; this.baseDelay = baseDelay; this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts; this.processingTimeout = processingTimeout;
        this.metrics = metrics;
        if (handlerConcurrency < 1 || handlerConcurrency > 64)
            throw new IllegalArgumentException("Event handler concurrency must be between 1 and 64.");
        this.handlerPool = new ThreadPoolExecutor(handlerConcurrency, handlerConcurrency, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(handlerConcurrency), runnable -> {
                Thread thread = new Thread(runnable, "stockline-event-handler");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        EnumMap<EventType, OutboxEventHandler> registered = new EnumMap<>(EventType.class);
        for (OutboxEventHandler handler : handlers) {
            if (registered.put(handler.eventType(), handler) != null)
                throw new IllegalStateException("Multiple consumers registered for " + handler.eventType());
        }
        this.handlers = Map.copyOf(registered);
    }

    public EventProcessor(Store store, List<OutboxEventHandler> handlers, EventProcessingRuntime runtime,
            Duration baseDelay, Duration maxDelay, int maxAttempts, Duration processingTimeout) {
        this(store, handlers, runtime, baseDelay, maxDelay, maxAttempts, processingTimeout, 1, new StocklineMetrics());
    }

    public void process(OutboxEvent event, String workerId) {
        UUID correlationId = event.requestCorrelationId() != null ? event.requestCorrelationId() : event.correlationId();
        UUID previousCorrelation = CorrelationContext.replace(correlationId);
        String previousMdc = MDC.get("correlation_id");
        if (correlationId != null) MDC.put("correlation_id", correlationId.toString());
        try { processCorrelated(event, workerId); }
        finally {
            if (previousMdc == null) MDC.remove("correlation_id"); else MDC.put("correlation_id", previousMdc);
            CorrelationContext.restore(previousCorrelation);
        }
    }

    private void processCorrelated(OutboxEvent event, String workerId) {
        long started = System.nanoTime();
        try {
            EventType type;
            try { type = EventType.valueOf(event.eventType()); }
            catch (IllegalArgumentException invalidType) {
                throw new PermanentEventException("Event type is not supported.");
            }
            OutboxEventHandler handler = handlers.get(type);
            if (handler == null) throw new PermanentEventException("No event consumer is registered.");
            UUID handlerCorrelation = CorrelationContext.current();
            String handlerMdc = MDC.get("correlation_id");
            Future<?> task = handlerPool.submit(() -> {
                UUID previousCorrelation = CorrelationContext.replace(handlerCorrelation);
                String previousMdc = MDC.get("correlation_id");
                if (handlerMdc != null) MDC.put("correlation_id", handlerMdc);
                try { handler.handle(event); }
                finally {
                    if (previousMdc == null) MDC.remove("correlation_id"); else MDC.put("correlation_id", previousMdc);
                    CorrelationContext.restore(previousCorrelation);
                }
            });
            try {
                task.get(processingTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                task.cancel(true);
                throw new RetryableEventTimeout("Event exceeded its processing timeout.");
            } catch (InterruptedException interrupted) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw new RetryableEventTimeout("Event processing was interrupted.");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                throw new IllegalStateException("Event handler failed.", cause);
            }
            if (store.completeEvent(event.eventId(), workerId, InventoryService.now())) {
                runtime.completed();
                metrics.eventCompleted(event.eventType(), System.nanoTime() - started);
            }
        } catch (RuntimeException failure) {
            String failureKind = permanent(failure) ? "PERMANENT" : "RETRYABLE";
            String summary = safeMessage(failure);
            OffsetDateTime now = InventoryService.now();
            boolean persisted = store.failEvent(event.eventId(), workerId, now, now.plus(backoff(event.attemptCount())),
                maxAttempts, failureKind, summary);
            if (persisted) {
                runtime.failed(event.eventType() + ": " + summary);
                metrics.eventHandlerFailure(event.eventType());
                boolean dead = "PERMANENT".equals(failureKind) || event.attemptCount() >= maxAttempts;
                if (dead) metrics.eventDeadLettered(event.eventType());
                else metrics.eventRetried(event.eventType());
            }
            log.warn("Event {} attempt {} ended as {}: {}", event.eventId(), event.attemptCount(), failureKind, summary);
        }
    }

    @Override public void destroy() { handlerPool.shutdownNow(); }

    private boolean permanent(RuntimeException failure) {
        if (failure instanceof PermanentEventException) return true;
        if (failure instanceof in.stockline.domain.exception.ApiProblem problem)
            return problem.status.is4xxClientError();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof java.sql.SQLTransientException)
                return false;
        }
        return false;
    }

    private Duration backoff(int attempt) {
        long cap = baseDelay.toMillis();
        long maximum = maxDelay.toMillis();
        for (int i = 1; i < attempt && cap < maximum; i++) cap = cap > maximum / 2 ? maximum : Math.min(maximum, cap * 2);
        long jitter = cap <= 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
        return Duration.ofMillis(jitter);
    }

    private String safeMessage(RuntimeException failure) {
        if (failure instanceof RetryableEventTimeout) return "Event processing exceeded its time limit.";
        if (!(failure instanceof PermanentEventException)
                && !(failure instanceof in.stockline.domain.exception.ApiProblem))
            return "Event handler failed (" + failure.getClass().getSimpleName() + ").";
        String message = failure.getMessage();
        String value = (message == null || message.isBlank()) ? failure.getClass().getSimpleName() : message;
        value = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return value.substring(0, Math.min(value.length(), 240));
    }

    private static final class RetryableEventTimeout extends RuntimeException {
        RetryableEventTimeout(String message) { super(message); }
    }
}
