package in.stockline.simulation.concurrency;

import in.stockline.api.dto.InventoryRequests.OrderInput;

/** Unprotected absolute read-modify-write, restricted to a disposable simulation database. */
public final class NaiveDatabaseRaceStrategy extends AbstractDatabaseRaceStrategy {
    @Override
    public String id() {
        return "NAIVE";
    }

    @Override
    public boolean reserve(RaceSimulationDatabase database, OrderInput order, Runnable afterRead,
                           RaceCounters counters) {
        return transact(database, order, afterRead, counters, false, true);
    }
}
