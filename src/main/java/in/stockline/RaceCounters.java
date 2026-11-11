package in.stockline;

import java.util.concurrent.atomic.LongAdder;

final class RaceCounters {
    final LongAdder conflicts = new LongAdder();
    final LongAdder retries = new LongAdder();
    final LongAdder failures = new LongAdder();
    final LongAdder deadlocks = new LongAdder();
    final LongAdder lockWaitNanos = new LongAdder();
    final LongAdder databaseNanos = new LongAdder();
    final LongAdder coordinationNanos = new LongAdder();
    final LongAdder queueNanos = new LongAdder();
}
