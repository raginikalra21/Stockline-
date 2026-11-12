# Concurrency Design

Stockline keeps each product and warehouse balance in its own `inventory` row. Reservation, order state, allocations, ledger movements, and outbox events are written in one database transaction. The production inventory service uses bounded retries and version checks; all stock updates follow SKU and warehouse-code ordering for multi-row operations.

## Strategies

- **NAIVE** is an intentionally unsafe, unguarded absolute read-modify-write in `NaiveDatabaseRaceStrategy`. A barrier in the isolated simulation synchronizes stale reads so lost reservations can occur under `READ_COMMITTED`. It is not used by order APIs and its simulation database is disposed after each run.
- **OPTIMISTIC** invokes `InventoryService.place`. It plans from fresh persisted balances and applies version-conditional updates; a failed version predicate rolls back the order and retries with fresh state up to the configured limit. Conflicts, retries, exhausted attempts, and failed transactions are counted from real outcomes.
- **PESSIMISTIC** uses `Store.stockForSkuForUpdate`, which issues ordered `SELECT ... FOR UPDATE` against candidate inventory rows. Reservation, order, allocations, and ledger writes share the locked transaction. Lock wait and bounded database retries are measured. This is a database lock, not a JVM monitor.

## Fair comparisons

`RaceDemo` creates a new Flyway database for every strategy and repetition. Each starts with the same `RACE-01` stock distribution, workload, worker count, `READ_COMMITTED` isolation, and schema. Only the naive/optimistic schedules use a simulation coordination barrier; the barrier time is reported separately from database work. Result fields come from committed rows and measured request timings, including accepted/rejected/failed counts, oversell, lost updates, conflicts, retries, lock waits, latency percentiles, throughput, final balances, and ledger reconciliation.

The default experiment is 100 buyers, 10 starting units, and one unit per buyer. A naive run may or may not expose a lost update; the UI reports the measured result. Safe strategies are correct only when balances remain nonnegative, accepted quantities match persisted reservations, and ledger reconciliation passes. A transient bounded-retry failure is counted and does not silently become an accepted reservation.

## Database coverage

H2 provides a repeatable local lab and unit integration tests, but its lock scheduling does not prove PostgreSQL behavior. `PostgresIntegrationTest` uses PostgreSQL Testcontainers, isolated schemas, competing transactions, and a held row lock to verify that a pessimistic reservation waits on PostgreSQL before committing. PostgreSQL tests are skipped when Docker is unavailable. H2 timings are not production performance estimates.
