package in.stockline.simulation.concurrency;

import in.stockline.api.dto.InventoryRequests.OrderInput;

public interface RaceStrategy {
    String id();

    /** Returns false only for a real stock-shortage rejection. */
    boolean reserve(RaceSimulationDatabase database, OrderInput order, Runnable afterRead,
                    RaceCounters counters);
}
