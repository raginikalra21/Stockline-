package in.stockline.simulation.concurrency;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public final class SimulationModels {
    private SimulationModels() {}

    public record RaceInput(@NotNull @Min(2) @Max(1_000) Integer buyers,
                            @NotNull @Min(1) @Max(1_000_000) Integer stock,
                            @NotNull @Min(1) @Max(10_000) Integer quantity,
                            @NotNull @Min(1) @Max(32) Integer warehouses,
                            @NotNull @Min(1) @Max(128) Integer workers,
                            @NotNull @Min(1) @Max(300) Integer durationSeconds,
                            @NotNull @Pattern(regexp = "COMPARE|UNSAFE|NAIVE|OPTIMISTIC|PESSIMISTIC") String mode,
                            @NotNull @Min(1) @Max(5) Integer repetitions) {
        public RaceInput {
            if (warehouses == null) warehouses = 1;
            if (workers == null) workers = buyers == null ? 1 : Math.min(32, buyers);
            if (durationSeconds == null) durationSeconds = 60;
            if (mode == null) mode = "COMPARE";
            if (repetitions == null) repetitions = 1;
        }
        public RaceInput(Integer buyers, Integer stock, Integer quantity, Integer warehouses, Integer workers,
                         Integer durationSeconds, String mode) {
            this(buyers, stock, quantity, warehouses, workers, durationSeconds, mode, 1);
        }
        public RaceInput(Integer buyers, Integer stock, Integer quantity) {
            this(buyers, stock, quantity, 1, Math.min(32, buyers), 60, "COMPARE", 1);
        }
    }
    public record RaceModeResult(String mode, int attempts, int accepted, int rejected, long oversold,
                                 long optimisticConflicts, long retryAttempts, long failedTransactions,
                                 long finalAvailable, long durationMs, double requestsPerSecond,
                                 double averageLatencyMs, double p50LatencyMs, double p95LatencyMs,
                                 double p99LatencyMs, boolean correct, long lostUpdates, long deadlocks,
                                 double lockWaitMs, double queueWaitMs, double databaseTimeMs,
                                 String isolationLevel) {}
    public record RaceRound(int repetition, List<RaceModeResult> strategies) {}
    public record RaceProgress(String status, int total, int completed, int accepted, int rejected,
                               long currentAvailable, long conflicts, long retries, RaceResult result) {}
    public record RaceJob(String id, String status) {}
    public record RaceResult(int buyers, int stock, int quantity, int accepted, int rejected,
                             long optimisticConflicts, long remaining, long reserved, long shipped,
                             boolean verified, RaceModeResult unsafe, RaceModeResult optimistic,
                             RaceModeResult pessimistic, List<RaceRound> rounds) {
        public RaceResult(int buyers, int stock, int quantity, int accepted, int rejected,
                          long optimisticConflicts, long remaining, long reserved, long shipped, boolean verified) {
            this(buyers, stock, quantity, accepted, rejected, optimisticConflicts, remaining,
                reserved, shipped, verified, null, null, null, List.of());
        }
    }
}
