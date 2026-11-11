package in.stockline;

import in.stockline.ApiProblem;
import in.stockline.Models.OrderInput;

final class OptimisticDatabaseRaceStrategy implements RaceStrategy {
    @Override
    public String id() {
        return "OPTIMISTIC";
    }

    @Override
    public boolean reserve(RaceSimulationDatabase database, OrderInput order, Runnable afterRead,
                           RaceCounters counters) {
        try {
            database.inventory.place(order, afterRead, counters.conflicts::increment,
                counters.retries::increment, counters.databaseNanos::add);
            return true;
        } catch (ApiProblem shortage) {
            if ("INSUFFICIENT_STOCK".equals(shortage.code)) return false;
            throw shortage;
        }
    }
}
