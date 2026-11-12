package in.stockline.simulation.concurrency;

import in.stockline.api.dto.InventoryRequests.OrderInput;

/** Uses ordered SELECT FOR UPDATE locks and commits the reservation and ledger atomically. */
public final class PessimisticDatabaseRaceStrategy extends AbstractDatabaseRaceStrategy {
    @Override
    public String id() {
        return "PESSIMISTIC";
    }

    @Override
    public boolean reserve(RaceSimulationDatabase database, OrderInput order, Runnable afterRead,
                           RaceCounters counters) {
        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                return transact(database, order, () -> {}, counters, true, false);
            } catch (RuntimeException failure) {
                if (!retryable(failure)) throw failure;
                if (isDeadlock(failure)) counters.deadlocks.increment();
                if (attempt == 8 || Thread.currentThread().isInterrupted()) throw failure;
                counters.retries.increment();
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Pessimistic reservation retry limit reached.");
    }
}
