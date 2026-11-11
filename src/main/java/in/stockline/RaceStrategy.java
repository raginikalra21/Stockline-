package in.stockline;

import in.stockline.Models.OrderInput;

interface RaceStrategy {
    String id();

    /** Returns false only for a real stock-shortage rejection. */
    boolean reserve(RaceSimulationDatabase database, OrderInput order, Runnable afterRead,
                    RaceCounters counters);
}
