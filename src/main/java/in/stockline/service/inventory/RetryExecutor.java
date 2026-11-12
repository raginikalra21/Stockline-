package in.stockline.service.inventory;

import in.stockline.domain.exception.ApiProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import in.stockline.observability.StocklineMetrics;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

@Component
public final class RetryExecutor {
    public static final class VersionConflict extends RuntimeException {}

    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final StocklineMetrics metrics;

    @Autowired
    public RetryExecutor(PlatformTransactionManager manager,
                         @Value("${stockline.retry.max-attempts:8}") int maxAttempts,
                         @Value("${stockline.retry.base-delay:2ms}") Duration baseDelay,
                         @Value("${stockline.retry.max-delay:100ms}") Duration maxDelay,
                         StocklineMetrics metrics) {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("stockline.retry.max-attempts must be between 1 and 100");
        }
        if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0
            || maxDelay.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Retry delays must be positive, ordered, and at most 10 seconds");
        }
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.metrics = metrics;
    }

    public RetryExecutor(PlatformTransactionManager manager, int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this(manager, maxAttempts, baseDelay, maxDelay, new StocklineMetrics());
    }

    public <T> T execute(Supplier<T> operation) {
        return execute(operation, () -> {}, () -> {}, ignored -> {});
    }

    public <T> T execute(Supplier<T> operation, Runnable onVersionConflict, Runnable onRetry,
                         LongConsumer onTransactionNanos) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean retryable = false;
            try {
                long started = System.nanoTime();
                try {
                    return Objects.requireNonNull(transaction.execute(status -> operation.get()));
                } finally {
                    onTransactionNanos.accept(System.nanoTime() - started);
                }
            } catch (VersionConflict conflict) {
                metrics.retryConflict();
                onVersionConflict.run();
                retryable = true;
            } catch (ConcurrencyFailureException contention) {
                metrics.retryConflict();
                retryable = true;
            } catch (QueryTimeoutException lockTimeout) {
                if (isRetryableLockTimeout(lockTimeout)) { metrics.retryConflict(); retryable = true; }
                else throw lockTimeout;
            } catch (DataAccessResourceFailureException unavailable) {
                metrics.failedTransaction();
                throw ApiProblem.databaseUnavailable("The inventory database is temporarily unavailable.");
            } catch (DuplicateKeyException duplicate) {
                throw duplicate;
            } catch (RuntimeException failure) {
                metrics.failedTransaction();
                throw failure;
            }

            if (!retryable || attempt == maxAttempts || Thread.currentThread().isInterrupted()) {
                if (retryable && attempt == maxAttempts) metrics.retryExhausted();
                break;
            }
            metrics.retryAttempt();
            onRetry.run();
            LockSupport.parkNanos(fullJitterNanos(attempt));
            if (Thread.currentThread().isInterrupted()) break;
        }
        throw ApiProblem.busy("Inventory is busy. Retry this request.");
    }

    private long fullJitterNanos(int failedAttempt) {
        long cap = baseDelay.toNanos();
        long maximum = maxDelay.toNanos();
        for (int exponent = 1; exponent < failedAttempt && cap < maximum; exponent++) {
            cap = cap > maximum / 2 ? maximum : Math.min(maximum, cap * 2);
        }
        return ThreadLocalRandom.current().nextLong(cap + 1);
    }

    private boolean isRetryableLockTimeout(QueryTimeoutException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                && (sql.getErrorCode() == 50200 || "40001".equals(sql.getSQLState()))) return true;
        }
        return false;
    }
}
