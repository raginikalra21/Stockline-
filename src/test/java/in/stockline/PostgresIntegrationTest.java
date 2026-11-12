package in.stockline;

import in.stockline.domain.model.Models;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import in.stockline.service.inventory.InventoryLedgerBootstrap;
import in.stockline.service.audit.InventoryAuditService;
import in.stockline.events.consumer.FulfillmentWorker;
import in.stockline.simulation.concurrency.RaceDemo;
import in.stockline.simulation.concurrency.RaceCounters;
import in.stockline.simulation.concurrency.RaceSimulationDatabase;
import in.stockline.simulation.concurrency.RaceStrategy;
import in.stockline.simulation.concurrency.NaiveDatabaseRaceStrategy;
import in.stockline.simulation.concurrency.OptimisticDatabaseRaceStrategy;
import in.stockline.simulation.concurrency.PessimisticDatabaseRaceStrategy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static in.stockline.domain.model.Models.*;
import static in.stockline.api.dto.InventoryRequests.*;
import static in.stockline.simulation.concurrency.SimulationModels.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.profiles.active=postgres", "stockline.fulfillment-delay=1h",
    "stockline.events.initial-delay-ms=3600000"})
class PostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("stockline")
        .withUsername("stockline")
        .withPassword("stockline-test-only");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("DB_URL", POSTGRES::getJdbcUrl);
        properties.add("DB_USERNAME", POSTGRES::getUsername);
        properties.add("DB_PASSWORD", POSTGRES::getPassword);
        properties.add("JWT_SECRET", () -> "VGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS0zMi1ieXRlcy1taW5pbXVt");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
    }

    @Autowired InventoryService inventory;
    @Autowired Store store;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryAuditService audit;

    @Test
    void flywayReservationIdempotencyRollbackCancellationFulfillmentAndLedgerWorkOnPostgres() {
        assertThat(jdbc.queryForObject("SELECT MAX(version) FROM flyway_schema_history", Integer.class))
            .isEqualTo(9);
        inventory.addProduct(new ProductInput("PG-ORDER", "Postgres order test", "Integration"));
        inventory.restock(new RestockInput("PG-ORDER", "PUN", 10));
        inventory.restock(new RestockInput("PG-ORDER", "DEL", 4));

        OrderInput firstRequest = new OrderInput("Postgres buyer", UUID.randomUUID(),
            List.of(new OrderLine("PG-ORDER", 12)), "PUN");
        Placement first = inventory.place(firstRequest);
        assertThat(first.order().allocations()).containsExactlyInAnyOrder(
            new Allocation("PG-ORDER", "PUN", 10), new Allocation("PG-ORDER", "DEL", 2));
        assertThat(inventory.place(firstRequest).replayed()).isTrue();
        assertThat(inventory.fulfill(first.order().id())).isTrue();
        assertThat(inventory.order(first.order().id()).status()).isEqualTo(OrderStatus.FULFILLED);

        Order cancellable = inventory.place(new OrderInput("Cancel buyer", UUID.randomUUID(),
            List.of(new OrderLine("PG-ORDER", 1)), "DEL")).order();
        assertThat(inventory.cancel(cancellable.id()).status()).isEqualTo(OrderStatus.CANCELLED);

        inventory.addProduct(new ProductInput("PG-ROLLBACK", "Rollback test", "Integration"));
        inventory.restock(new RestockInput("PG-ROLLBACK", "PUN", 1));
        Stock before = store.stock("PG-ORDER", "PUN");
        long ordersBefore = store.overview().orders();
        OrderInput rollback = new OrderInput("Rollback buyer", UUID.randomUUID(), List.of(
            new OrderLine("PG-ORDER", 1), new OrderLine("PG-ROLLBACK", 2)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inventory.place(rollback))
            .isInstanceOf(ApiProblem.class);
        assertThat(store.stock("PG-ORDER", "PUN")).isEqualTo(before);
        assertThat(store.overview().orders()).isEqualTo(ordersBefore);
        assertThat(audit.reconcile()).allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE product_id = 'PG-ORDER'",
            Integer.class)).isPositive();
    }

    @Test
    void postgresSkipLockedClaimsDoNotGiveTheSameEventToConcurrentWorkers() throws Exception {
        // Keep the application worker from racing the explicit SKIP LOCKED assertions below.
        // Sort this batch ahead of pending events left by other tests in the shared database.
        var now = InventoryService.now().plusMinutes(10);
        var queuedAt = now.minusYears(10);
        for (int index = 0; index < 24; index++) {
            UUID eventId = UUID.randomUUID();
            store.enqueueEvent(eventId, EventType.INVENTORY_ADJUSTED, "INVENTORY", "PG-CLAIM-" + index,
                java.util.Map.of("schemaVersion", 1), UUID.randomUUID(), "PG_CLAIM:" + eventId, queuedAt,
                queuedAt);
        }

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var left = pool.submit(() -> {
                start.await();
                return store.claimEvents("postgres-worker-left", 12, now, java.time.Duration.ofMinutes(1));
            });
            var right = pool.submit(() -> {
                start.await();
                return store.claimEvents("postgres-worker-right", 12, now, java.time.Duration.ofMinutes(1));
            });
            start.countDown();
            var claimed = new java.util.ArrayList<OutboxEvent>();
            claimed.addAll(left.get(10, TimeUnit.SECONDS));
            claimed.addAll(right.get(10, TimeUnit.SECONDS));
            var selected = claimed.stream().filter(event -> event.aggregateId().startsWith("PG-CLAIM-")).toList();
            assertThat(selected).hasSize(24);
            assertThat(selected).extracting(OutboxEvent::eventId).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReservationsAndTransfersRemainConsistentOnPostgres() throws Exception {
        inventory.addProduct(new ProductInput("PG-CONCURRENT", "Postgres concurrency test", "Integration"));
        inventory.restock(new RestockInput("PG-CONCURRENT", "PUN", 50));
        inventory.restock(new RestockInput("PG-CONCURRENT", "DEL", 30));
        inventory.restock(new RestockInput("PG-CONCURRENT", "BLR", 20));

        var pool = Executors.newFixedThreadPool(10);
        var start = new CountDownLatch(1);
        try {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 10; i++) {
                int buyer = i;
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        inventory.place(new OrderInput("Concurrent " + buyer, UUID.randomUUID(),
                            List.of(new OrderLine("PG-CONCURRENT", 10)), "PUN"));
                        return true;
                    } catch (ApiProblem shortage) {
                        if (!"INSUFFICIENT_STOCK".equals(shortage.code)) throw shortage;
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(attempts.stream().filter(future -> get(future)).count()).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.stockForSku("PG-CONCURRENT")).allSatisfy(stock -> {
            assertThat(stock.available()).isGreaterThanOrEqualTo(0);
            assertThat(stock.reserved()).isGreaterThanOrEqualTo(0);
        });
        assertThat(store.stockForSku("PG-CONCURRENT").stream().mapToLong(Stock::reserved).sum()).isEqualTo(100);

        inventory.restock(new RestockInput("PG-CONCURRENT", "PUN", 20));
        var transferPool = Executors.newFixedThreadPool(8);
        var transferStart = new CountDownLatch(1);
        try {
            var transfers = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                int request = i;
                transfers.add(transferPool.submit(() -> {
                    transferStart.await();
                    try {
                        inventory.transfer(new TransferInput("PG-CONCURRENT", "PUN", "BLR", 5,
                            UUID.nameUUIDFromBytes(("pg-transfer-" + request).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                        return true;
                    } catch (ApiProblem shortage) {
                        if (!"INSUFFICIENT_STOCK".equals(shortage.code)) throw shortage;
                        return false;
                    }
                }));
            }
            transferStart.countDown();
            assertThat(transfers.stream().filter(future -> get(future)).count()).isEqualTo(4);
        } finally {
            transferPool.shutdownNow();
        }
        long physical = store.stockForSku("PG-CONCURRENT").stream()
            .mapToLong(stock -> stock.available() + stock.reserved() + stock.damaged() + stock.shipped()).sum();
        assertThat(physical).isEqualTo(120);
        assertThat(audit.reconcile()).allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));
    }

    @Test
    void allThreeConcurrencyStrategiesRunAgainstPostgresWithIsolatedSchemas() throws Exception {
        RaceInput input = new RaceInput(2, 2, 2, 2, 2, 30, "COMPARE", 1);
        var naive = runStrategyOnPostgres(input, new NaiveDatabaseRaceStrategy(), true);
        var optimistic = runStrategyOnPostgres(input, new OptimisticDatabaseRaceStrategy(), true);
        var pessimistic = runStrategyOnPostgres(input, new PessimisticDatabaseRaceStrategy(), false);

        assertThat(naive).containsExactly(2, 2);
        assertThat(optimistic).containsExactly(1, 2);
        assertThat(pessimistic).containsExactly(1, 2);
    }

    @Test
    void productionRaceSimulationFactoryUsesAndDropsAnIsolatedPostgresSchema() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        int schemasBefore = admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata "
            + "WHERE schema_name LIKE 'stockline_race_%'", Integer.class);
        var factory = new in.stockline.simulation.concurrency.PostgresRaceSimulationDatabaseFactory(
            admin.getDataSource(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        RaceInput input = new RaceInput(2, 10, 1, 2, 4, 30, "PESSIMISTIC", 1);

        try (RaceSimulationDatabase isolated = factory.create(input)) {
            assertThat(isolated.store.stockForSku("RACE-01").stream()
                .mapToLong(stock -> stock.available() + stock.reserved()).sum()).isEqualTo(10);
            assertThat(new InventoryAuditService(isolated.store).reconcile())
                .allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));
        }

        int schemasAfter = admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata "
            + "WHERE schema_name LIKE 'stockline_race_%'", Integer.class);
        assertThat(schemasAfter).isEqualTo(schemasBefore);
    }

    @Test
    void pessimisticPostgresReservationWaitsOnTheDatabaseRowLock() throws Exception {
        RaceInput input = new RaceInput(2, 2, 1, 2, 2, 30, "PESSIMISTIC", 1);
        String schema = "lock_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
        HikariConfig config = new HikariConfig();
        String url = POSTGRES.getJdbcUrl();
        config.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        HikariDataSource dataSource = new HikariDataSource(config);
        AutoCloseable cleanup = () -> {
            dataSource.close();
            new JdbcTemplate(admin).execute("DROP SCHEMA " + schema + " CASCADE");
        };
        try (RaceSimulationDatabase isolated = new RaceSimulationDatabase(input, dataSource, true, cleanup);
             var blocker = isolated.dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT available FROM inventory WHERE sku='RACE-01' AND warehouse_code='W01' FOR UPDATE").executeQuery()) {
                assertThat(lock.next()).isTrue();
            }
            RaceCounters counters = new RaceCounters();
            var executor = Executors.newSingleThreadExecutor();
            try {
                var reservation = executor.submit(() -> new PessimisticDatabaseRaceStrategy().reserve(isolated,
                    new OrderInput("Lock wait", UUID.randomUUID(), List.of(new OrderLine("RACE-01", 1))),
                    () -> {}, counters));
                Thread.sleep(200);
                assertThat(reservation.isDone()).isFalse();
                blocker.commit();
                assertThat(reservation.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(counters.lockWaitNanos.sum()).isPositive();
                assertThat(isolated.store.stockForSku("RACE-01").stream().mapToLong(Stock::reserved).sum()).isEqualTo(1);
                assertThat(new InventoryAuditService(isolated.store).reconcile())
                    .allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void databaseServerRestartPreservesCommittedStock() throws Exception {
        inventory.addProduct(new ProductInput("PG-RESTART", "Postgres restart test", "Integration"));
        inventory.restock(new RestockInput("PG-RESTART", "PUN", 7));
        POSTGRES.execInContainer("pg_ctl", "-D", "/var/lib/postgresql/data", "restart", "-m", "fast", "-w");
        awaitPostgres();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                "SELECT available FROM inventory WHERE sku = ? AND warehouse_code = ?")) {
            statement.setString(1, "PG-RESTART");
            statement.setString(2, "PUN");
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(7);
            }
        }
    }

    private List<Integer> runStrategyOnPostgres(RaceInput input, RaceStrategy strategy, boolean barrier)
        throws Exception {
        String schema = "race_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
        HikariConfig config = new HikariConfig();
        String postgresUrl = POSTGRES.getJdbcUrl();
        config.setJdbcUrl(postgresUrl + (postgresUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(8);
        HikariDataSource dataSource = new HikariDataSource(config);
        AutoCloseable cleanup = () -> {
            dataSource.close();
            new JdbcTemplate(admin).execute("DROP SCHEMA " + schema + " CASCADE");
        };
        try (RaceSimulationDatabase database = new RaceSimulationDatabase(input, dataSource, true, cleanup)) {
            var begin = new CountDownLatch(1);
            var ready = new java.util.concurrent.CyclicBarrier(2);
            var reads = new AtomicInteger();
            var callbacks = new java.util.function.Supplier<Runnable>() {
                @Override public Runnable get() {
                    return () -> {
                        if (!barrier || reads.getAndIncrement() >= 2) return;
                        try { ready.await(10, TimeUnit.SECONDS); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    };
                }
            };
            var counters = List.of(new RaceCounters(), new RaceCounters());
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> {
                    await(begin);
                    return strategy.reserve(database, new OrderInput("Buyer one", UUID.randomUUID(),
                        List.of(new OrderLine("RACE-01", 2))), callbacks.get(), counters.get(0));
                });
                var second = pool.submit(() -> {
                    await(begin);
                    return strategy.reserve(database, new OrderInput("Buyer two", UUID.randomUUID(),
                        List.of(new OrderLine("RACE-01", 2))), callbacks.get(), counters.get(1));
                });
                begin.countDown();
                int accepted = (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
                int reserved = database.store.stockForSku("RACE-01").stream().mapToInt(stock -> (int) stock.reserved()).sum();
                return List.of(accepted, reserved);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private void awaitPostgres() throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            try (var connection = java.sql.DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                return;
            } catch (java.sql.SQLException retry) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("PostgreSQL did not restart within 15 seconds.");
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static <T> T get(java.util.concurrent.Future<T> future) {
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
