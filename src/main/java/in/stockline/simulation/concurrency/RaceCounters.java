package in.stockline.simulation.concurrency;

import java.util.concurrent.atomic.LongAdder;

public final class RaceCounters {
    public final LongAdder conflicts = new LongAdder();
    public final LongAdder retries = new LongAdder();
    public final LongAdder failures = new LongAdder();
    public final LongAdder deadlocks = new LongAdder();
    public final LongAdder lockWaitNanos = new LongAdder();
    public final LongAdder databaseNanos = new LongAdder();
    public final LongAdder coordinationNanos = new LongAdder();
    public final LongAdder queueNanos = new LongAdder();
}
