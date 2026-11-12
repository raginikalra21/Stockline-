package in.stockline.simulation.concurrency;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import in.stockline.service.audit.InventoryAuditService;
import in.stockline.events.consumer.FulfillmentWorker;
import static in.stockline.api.dto.InventoryRequests.*;
import static in.stockline.domain.model.Models.*;
import static in.stockline.simulation.concurrency.SimulationModels.*;

@Service
public class RaceDemo implements DisposableBean {
    private static final int MAX_RETAINED_JOBS = 100;
    private static final List<RaceStrategy> STRATEGIES = List.of(new NaiveDatabaseRaceStrategy(),
        new OptimisticDatabaseRaceStrategy(), new PessimisticDatabaseRaceStrategy());
    private final RaceSimulationDatabaseFactory databaseFactory;
    private final Semaphore slot = new Semaphore(1);
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final ConcurrentMap<String, JobState> states = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> jobOrder = new ConcurrentLinkedDeque<>();

    public RaceDemo() { this(RaceSimulationDatabase::new); }

    @Autowired
    public RaceDemo(RaceSimulationDatabaseFactory databaseFactory) { this.databaseFactory = databaseFactory; }

    @Override
    public void destroy() {
        for (String id : states.keySet()) cancel(id);
        jobs.shutdownNow();
        try {
            jobs.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class JobState {
        volatile int total;
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicLong available = new AtomicLong();
        final AtomicLong conflicts = new AtomicLong();
        final AtomicLong retries = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean slotReleased = new AtomicBoolean();
        final Object lifecycle = new Object();
        volatile Future<?> task;
        boolean started;
        volatile String status = "QUEUED";
        volatile RaceResult result;
    }

    private record StrategyRun(RaceModeResult result, long remaining, long reserved, long shipped,
                               boolean verified) {}

    public RaceJob start(RaceInput input) {
        validate(input);
        if (!slot.tryAcquire()) throw ApiProblem.busy("A simulation is already running. Try again in a moment.");
        pruneCompletedJobs();
        String id = UUID.randomUUID().toString();
        JobState state = new JobState();
        state.total = input.buyers() * selectedStrategies(input.mode()).size() * input.repetitions();
        state.available.set(input.stock());
        states.put(id, state);
        jobOrder.addLast(id);
        try {
            Future<?> task = jobs.submit(() -> execute(input, state));
            synchronized (state.lifecycle) {
                state.task = task;
                if (state.cancelled.get()) task.cancel(true);
            }
        } catch (RuntimeException rejected) {
            states.remove(id);
            jobOrder.remove(id);
            releaseSlot(state);
            throw ApiProblem.busy("The simulation worker is unavailable. Try again.");
        }
        return new RaceJob(id, state.status);
    }

    private void execute(RaceInput input, JobState state) {
        synchronized (state.lifecycle) {
            if (state.cancelled.get()) {
                state.status = "CANCELLED";
                releaseSlot(state);
                return;
            }
            state.started = true;
        }
        try {
            List<RaceRound> rounds = new ArrayList<>();
            StrategyRun primary = null;
            StrategyRun optimisticSummary = null;
            StrategyRun pessimisticSummary = null;
            for (int repetition = 1; repetition <= input.repetitions(); repetition++) {
                List<RaceModeResult> roundResults = new ArrayList<>();
                for (RaceStrategy strategy : selectedStrategies(input.mode())) {
                    ensureNotCancelled(state);
                    state.status = "RUNNING_" + strategy.id();
                    state.available.set(input.stock());
                    StrategyRun run = executeStrategy(input, state, strategy);
                    roundResults.add(run.result());
                    primary = run;
                    if (strategy.id().equals("OPTIMISTIC")) optimisticSummary = run;
                    if (strategy.id().equals("PESSIMISTIC")) pessimisticSummary = run;
                }
                rounds.add(new RaceRound(repetition, List.copyOf(roundResults)));
            }
            RaceModeResult naive = findLast(rounds, "NAIVE");
            RaceModeResult optimistic = findLast(rounds, "OPTIMISTIC");
            RaceModeResult pessimistic = findLast(rounds, "PESSIMISTIC");
            synchronized (state.lifecycle) {
                if (state.cancelled.get()) {
                    state.status = "CANCELLED";
                } else {
                    StrategyRun summaryRun = optimisticSummary != null ? optimisticSummary
                        : pessimisticSummary != null ? pessimisticSummary : primary;
                    RaceModeResult summary = summaryRun.result();
                    state.result = new RaceResult(input.buyers(), input.stock(), input.quantity(), summary.accepted(),
                        summary.rejected(), summary.optimisticConflicts(), summaryRun.remaining(), summaryRun.reserved(),
                        summaryRun.shipped(), summaryRun.verified(), naive, optimistic, pessimistic, List.copyOf(rounds));
                    state.status = "COMPLETED";
                }
            }
        } catch (RuntimeException failure) {
            state.status = state.cancelled.get() ? "CANCELLED" : "FAILED";
        } finally {
            releaseSlot(state);
        }
    }

    private StrategyRun executeStrategy(RaceInput input, JobState state, RaceStrategy strategy) {
        try (RaceSimulationDatabase database = databaseFactory.create(input)) {
            RaceCounters counters = new RaceCounters();
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            List<Long> latency = Collections.synchronizedList(new ArrayList<>());
            long conflictBase = state.conflicts.get();
            long retryBase = state.retries.get();
            int workers = Math.min(input.workers(), input.buyers());
            int waves = (input.buyers() + workers - 1) / workers;
            List<CyclicBarrier> barriers = barriers(input, strategy, workers, waves);
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(input.durationSeconds());
            List<Future<?>> futures = new ArrayList<>(input.buyers());
            for (int buyer = 0; buyer < input.buyers(); buyer++) {
                int buyerNumber = buyer;
                long submitted = System.nanoTime();
                futures.add(pool.submit(() -> {
                    long began = System.nanoTime();
                    counters.queueNanos.add(began - submitted);
                    try {
                        ensureNotCancelled(state);
                        OrderInput order = new OrderInput("Buyer " + buyerNumber, UUID.randomUUID(),
                            List.of(new OrderLine("RACE-01", input.quantity())));
                        Runnable coordinate = barriers.isEmpty() ? () -> {} : () -> {
                            long coordinationStarted = System.nanoTime();
                            try {
                                await(barriers.get(buyerNumber / workers), deadline);
                            } finally {
                                counters.coordinationNanos.add(System.nanoTime() - coordinationStarted);
                            }
                        };
                        if (strategy.reserve(database, order, coordinate, counters)) {
                            accepted.incrementAndGet();
                            state.accepted.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                            state.rejected.incrementAndGet();
                        }
                    } catch (RuntimeException failure) {
                        failures.incrementAndGet();
                        counters.failures.increment();
                    } finally {
                        latency.add(System.nanoTime() - submitted);
                        state.completed.incrementAndGet();
                        state.conflicts.set(conflictBase + counters.conflicts.sum());
                        state.retries.set(retryBase + counters.retries.sum());
                        try {
                            state.available.set(database.store.stockForSku("RACE-01").stream()
                                .mapToLong(Stock::available).sum());
                        } catch (RuntimeException ignored) {
                            // The transaction's failure is already reflected in the failed request count.
                        }
                    }
                }));
            }
            awaitBatch(futures, pool, deadline);
            long durationNanos = System.nanoTime() - started;
            List<Stock> stocks = database.store.stockForSku("RACE-01");
            long finalAvailable = stocks.stream().mapToLong(Stock::available).sum();
            long reserved = stocks.stream().mapToLong(Stock::reserved).sum();
            long physical = stocks.stream().mapToLong(stock -> stock.available() + stock.reserved()
                + stock.damaged() + stock.shipped() + stock.writtenOff()).sum();
            long orderCount = database.jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Long.class);
            boolean ledgerValid = new InventoryAuditService(database.store).reconcile().stream()
                .allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));
            long lostUpdates = Math.max(0, (long) accepted.get() * input.quantity() - reserved);
            boolean correct = accepted.get() * (long) input.quantity() <= input.stock()
                && finalAvailable >= 0 && reserved == (long) accepted.get() * input.quantity()
                && physical == input.stock() && orderCount == accepted.get() && ledgerValid && lostUpdates == 0;
            RaceModeResult result = metrics(strategy.id(), input, accepted.get(), rejected.get(),
                counters, failures.get(), finalAvailable, durationNanos, latency, correct, lostUpdates);

            long remaining = finalAvailable;
            long shipped = stocks.stream().mapToLong(Stock::shipped).sum();
            boolean verified = correct;
            if (!strategy.id().equals("NAIVE")) {
                FulfillmentWorker worker = new FulfillmentWorker(database.store, database.inventory);
                int maxBatches = input.buyers() * 3 + 100;
                for (int batch = 0; batch < maxBatches; batch++) {
                    if (database.store.readyEventCount(InventoryService.now()) == 0) break;
                    worker.processDueOrders();
                }
                List<Stock> completedStocks = database.store.stockForSku("RACE-01");
                remaining = completedStocks.stream().mapToLong(Stock::available).sum();
                reserved = completedStocks.stream().mapToLong(Stock::reserved).sum();
                shipped = completedStocks.stream().mapToLong(Stock::shipped).sum();
                verified &= reserved == 0 && shipped == (long) accepted.get() * input.quantity()
                    && remaining + shipped == input.stock();
            }
            return new StrategyRun(result, remaining, reserved, shipped, verified);
        }
    }

    private List<CyclicBarrier> barriers(RaceInput input, RaceStrategy strategy, int workers, int waves) {
        if (!strategy.id().equals("NAIVE") && !strategy.id().equals("OPTIMISTIC")) return List.of();
        List<CyclicBarrier> result = new ArrayList<>(waves);
        for (int wave = 0; wave < waves; wave++) {
            int size = Math.min(workers, input.buyers() - wave * workers);
            result.add(new CyclicBarrier(size));
        }
        return result;
    }

    private RaceModeResult metrics(String mode, RaceInput input, int accepted, int rejected, RaceCounters counters,
                                   long failures, long remaining, long elapsedNanos, List<Long> samples,
                                   boolean correct, long lostUpdates) {
        List<Long> sorted;
        synchronized (samples) { sorted = samples.stream().sorted().toList(); }
        double seconds = Math.max(elapsedNanos / 1_000_000_000.0, 0.000001);
        double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        long oversold = Math.max(0, accepted * (long) input.quantity() - input.stock());
        return new RaceModeResult(mode, input.buyers(), accepted, rejected, oversold,
            counters.conflicts.sum(), counters.retries.sum(), failures, remaining,
            TimeUnit.NANOSECONDS.toMillis(elapsedNanos), input.buyers() / seconds, average,
            percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99), correct,
            lostUpdates, counters.deadlocks.sum(), counters.lockWaitNanos.sum() / 1_000_000.0,
            counters.queueNanos.sum() / 1_000_000.0,
            Math.max(0, counters.databaseNanos.sum() - counters.coordinationNanos.sum()) / 1_000_000.0,
            "READ_COMMITTED");
    }

    private double percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1))
            / 1_000_000.0;
    }

    private RaceModeResult findLast(List<RaceRound> rounds, String strategy) {
        return rounds.stream().flatMap(round -> round.strategies().stream())
            .filter(result -> result.mode().equals(strategy)).reduce((first, second) -> second).orElse(null);
    }

    private List<RaceStrategy> selectedStrategies(String mode) {
        if (mode.equals("COMPARE")) return STRATEGIES;
        String normalized = mode.equals("UNSAFE") ? "NAIVE" : mode;
        return STRATEGIES.stream().filter(strategy -> strategy.id().equals(normalized)).toList();
    }

    private void validate(RaceInput input) {
        if (input.quantity() > input.stock()) throw ApiProblem.invalid("Stock must cover at least one buyer.");
        long maximumRunSeconds = (long) input.durationSeconds() * selectedStrategies(input.mode()).size()
            * input.repetitions();
        if (maximumRunSeconds > 900) {
            throw ApiProblem.invalid("The combined comparison and repetition duration cannot exceed 15 minutes.");
        }
        if (slot.availablePermits() == 0)
            throw ApiProblem.busy("A simulation is already running. Try again in a moment.");
    }

    public RaceProgress cancel(String id) {
        JobState state = states.get(id);
        if (state == null) throw ApiProblem.notFound("Simulation does not exist.");
        synchronized (state.lifecycle) {
            if (isTerminal(state.status)) return progress(id);
            state.cancelled.set(true);
            state.status = "CANCELLING";
            Future<?> task = state.task;
            if (task != null) task.cancel(true);
            if (!state.started) {
                state.status = "CANCELLED";
                releaseSlot(state);
            }
        }
        return progress(id);
    }

    private void ensureNotCancelled(JobState state) {
        if (state.cancelled.get() || Thread.currentThread().isInterrupted())
            throw ApiProblem.busy("Simulation cancelled.");
    }

    private void pruneCompletedJobs() {
        while (states.size() >= MAX_RETAINED_JOBS) {
            String oldest = jobOrder.peekFirst();
            if (oldest == null) return;
            JobState state = states.get(oldest);
            if (state != null && !isTerminal(state.status)) return;
            jobOrder.pollFirst();
            states.remove(oldest);
        }
    }

    private boolean isTerminal(String status) {
        return status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED");
    }

    private void releaseSlot(JobState state) {
        if (state.slotReleased.compareAndSet(false, true)) slot.release();
    }

    public RaceProgress progress(String id) {
        JobState state = states.get(id);
        if (state == null) throw ApiProblem.notFound("Simulation does not exist.");
        return new RaceProgress(state.status, state.total, state.completed.get(), state.accepted.get(),
            state.rejected.get(), state.available.get(), state.conflicts.get(), state.retries.get(), state.result);
    }

    // Retained synchronous API for callers and integration tests.
    public RaceResult run(RaceInput input) {
        validate(input);
        if (!slot.tryAcquire()) throw ApiProblem.busy("A simulation is already running. Try again in a moment.");
        try {
            JobState state = new JobState();
            state.total = input.buyers() * selectedStrategies(input.mode()).size() * input.repetitions();
            List<RaceRound> rounds = new ArrayList<>();
            StrategyRun primary = null;
            StrategyRun optimisticSummary = null;
            StrategyRun pessimisticSummary = null;
            for (int repetition = 1; repetition <= input.repetitions(); repetition++) {
                List<RaceModeResult> results = new ArrayList<>();
                for (RaceStrategy strategy : selectedStrategies(input.mode())) {
                    primary = executeStrategy(input, state, strategy);
                    if (strategy.id().equals("OPTIMISTIC")) optimisticSummary = primary;
                    if (strategy.id().equals("PESSIMISTIC")) pessimisticSummary = primary;
                    results.add(primary.result());
                }
                rounds.add(new RaceRound(repetition, List.copyOf(results)));
            }
            StrategyRun summaryRun = optimisticSummary != null ? optimisticSummary
                : pessimisticSummary != null ? pessimisticSummary : primary;
            RaceModeResult summary = summaryRun.result();
            return new RaceResult(input.buyers(), input.stock(), input.quantity(), summary.accepted(), summary.rejected(),
                summary.optimisticConflicts(), summaryRun.remaining(), summaryRun.reserved(), summaryRun.shipped(),
                summaryRun.verified(), findLast(rounds, "NAIVE"), findLast(rounds, "OPTIMISTIC"),
                findLast(rounds, "PESSIMISTIC"), List.copyOf(rounds));
        } finally {
            slot.release();
        }
    }

    private void await(CyclicBarrier barrier, long deadline) {
        try {
            barrier.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiProblem.busy("Simulation interrupted.");
        } catch (BrokenBarrierException | TimeoutException failure) {
            throw ApiProblem.busy("Simulation duration exceeded.");
        }
    }

    private void awaitBatch(List<Future<?>> futures, ExecutorService pool, long deadline) {
        RuntimeException failure = null;
        boolean interrupted = false;
        try {
            for (Future<?> future : futures)
                future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
            failure = ApiProblem.busy("Simulation interrupted.");
        } catch (ExecutionException | TimeoutException exception) {
            failure = ApiProblem.busy("Simulation failed or exceeded its duration.");
        } finally {
            if (failure != null) futures.forEach(future -> future.cancel(true));
            if (failure == null) pool.shutdown(); else pool.shutdownNow();
            try {
                if (!pool.awaitTermination(6, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    if (!pool.awaitTermination(2, TimeUnit.SECONDS))
                        failure = ApiProblem.busy("Simulation workers did not stop after cancellation.");
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                pool.shutdownNow();
                failure = ApiProblem.busy("Simulation interrupted while stopping workers.");
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) throw failure;
    }
}
