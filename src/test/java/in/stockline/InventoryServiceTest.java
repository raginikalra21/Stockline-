package in.stockline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static in.stockline.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceTest {
    private EmbeddedDatabase db;
    private Store store;
    private InventoryService inventory;

    @BeforeEach
    void openDatabase() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
            .build();
        Flyway.configure().dataSource(db).locations("classpath:db/migration")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        store = new Store(new JdbcTemplate(db));
        var transactionManager = new JdbcTransactionManager(db);
        new InventoryLedgerBootstrap(store, transactionManager).initialize();
        inventory = new InventoryService(store, transactionManager, Duration.ZERO);
    }

    @AfterEach
    void closeDatabase() {
        db.shutdown();
    }

    @Test
    void splitsOrdersByWarehousePriorityAndFulfillsExactlyOnce() {
        Order order = inventory.place(order("KB-104", 10)).order();
        assertThat(order.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(order.allocations()).containsExactlyInAnyOrder(
            new Allocation("KB-104", "PUN", 8), new Allocation("KB-104", "DEL", 2));
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(new Stock("KB-104", "PUN", 0, 8, 0, 1));
        assertThat(store.events()).singleElement().extracting(OutboxEvent::status).isEqualTo("PENDING");

        var worker = new FulfillmentWorker(store, inventory);
        worker.processDueOrders();
        worker.processDueOrders();

        assertThat(inventory.order(order.id()).status()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(new Stock("KB-104", "PUN", 0, 0, 8, 2));
        assertThat(store.stock("KB-104", "DEL")).isEqualTo(new Stock("KB-104", "DEL", 12, 0, 2, 2));
        assertThat(store.events()).singleElement().extracting(OutboxEvent::status).isEqualTo("PROCESSED");
    }

    @Test
    void insufficientLineRollsBackTheWholeOrderAndReleasesItsRequestKey() {
        List<Stock> before = store.inventory();
        OrderInput input = new OrderInput("Buyer", UUID.randomUUID(),
            List.of(new OrderLine("CB-305", 1), new OrderLine("KB-104", 25)));

        assertThatThrownBy(() -> inventory.place(input)).isInstanceOfSatisfying(ApiProblem.class,
            error -> assertThat(error.code).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(store.inventory()).isEqualTo(before);
        assertThat(store.overview().orders()).isZero();
        assertThat(store.events()).isEmpty();

        inventory.restock(new RestockInput("KB-104", "PUN", 1));
        assertThat(inventory.place(input).order().allocations()).hasSize(4);
    }

    @Test
    void staleSecondSkuRollsBackTheEarlierReservationBeforeRetry() {
        var input = new OrderInput("Buyer", UUID.randomUUID(),
            List.of(new OrderLine("CB-305", 1), new OrderLine("KB-104", 24)));
        Stock cableBefore = store.stock("CB-305", "PUN");
        var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        var writer = Executors.newSingleThreadExecutor();
        try {
            Runnable concurrentBuyer = () -> {
                try {
                    writer.submit(() -> inventory.place(order("KB-104", 1))).get(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw new AssertionError(failure);
                }
            };
            assertThatThrownBy(() -> inventory.place(input, concurrentBuyer, conflicts::incrementAndGet))
                .isInstanceOfSatisfying(ApiProblem.class,
                    error -> assertThat(error.code).isEqualTo("INSUFFICIENT_STOCK"));
            assertThat(conflicts.get()).isEqualTo(1);
            assertThat(store.stock("CB-305", "PUN")).isEqualTo(cableBefore);
            assertThat(store.overview().orders()).isEqualTo(1);
            assertThat(store.events()).hasSize(1);
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    void repeatedCancellationReleasesEachAllocationOnce() {
        Order order = inventory.place(order("KB-104", 9)).order();
        assertThat(inventory.cancel(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventory.cancel(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventory.fulfill(order.id())).isFalse();
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(new Stock("KB-104", "PUN", 8, 0, 0, 2));
        assertThat(store.stock("KB-104", "DEL")).isEqualTo(new Stock("KB-104", "DEL", 14, 0, 0, 2));
        assertThat(store.events()).singleElement().extracting(OutboxEvent::status).isEqualTo("CANCELLED");
        assertThat(new InventoryAuditService(store).reconcile())
            .allMatch(line -> line.overallIntegrityStatus().equals("VALID"));
    }

    @Test
    void simultaneousDuplicateRequestsCreateOneOrderAndOneEvent() throws Exception {
        OrderInput input = order("KB-104", 2);
        List<Placement> results = concurrently(10, () -> inventory.place(input));
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(result -> result.order().id()).containsOnly(results.get(0).order().id());
        assertThat(store.stock("KB-104", "PUN").reserved()).isEqualTo(2);
        assertThat(store.events()).hasSize(1);
        assertThat(new JdbcTemplate(db).queryForObject("""
            SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 'RESERVATION'
              AND reference_order_id = ?
            """, Integer.class, results.get(0).order().id())).isEqualTo(2);

        var differentOrder = new OrderInput(input.customer(), input.requestKey(), List.of(new OrderLine("KB-104", 3)));
        assertThatThrownBy(() -> inventory.place(differentOrder)).isInstanceOfSatisfying(ApiProblem.class,
            error -> assertThat(error.code).isEqualTo("IDEMPOTENCY_MISMATCH"));
    }

    @Test
    void combinesDuplicateSkuLinesAndReplaysCanonicalPayload() {
        UUID key = UUID.randomUUID();
        var original = new OrderInput("Buyer", key,
            List.of(new OrderLine("CB-305", 1), new OrderLine("KB-104", 1), new OrderLine("CB-305", 2)));
        var equivalent = new OrderInput(" Buyer ", key,
            List.of(new OrderLine("KB-104", 1), new OrderLine("CB-305", 3)));

        Placement first = inventory.place(original);
        Placement second = inventory.place(equivalent);
        assertThat(second.replayed()).isTrue();
        assertThat(second.order().id()).isEqualTo(first.order().id());
        assertThat(first.order().allocations()).containsExactly(
            new Allocation("CB-305", "PUN", 3), new Allocation("KB-104", "PUN", 1));
    }

    @Test
    void fulfillmentAndCancellationHaveOnlyOneWinner() throws Exception {
        var order = inventory.place(order("KB-104", 6)).order();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            Future<Boolean> shipped = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return inventory.fulfill(order.id());
            });
            Future<Boolean> cancelled = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    return inventory.cancel(order.id()).status() == OrderStatus.CANCELLED;
                } catch (ApiProblem error) {
                    assertThat(error.code).isEqualTo("ALREADY_FULFILLED");
                    return false;
                }
            });
            boolean fulfilled = shipped.get(10, TimeUnit.SECONDS);
            assertThat(cancelled.get(10, TimeUnit.SECONDS)).isEqualTo(!fulfilled);
            Stock stock = store.stock("KB-104", "PUN");
            assertThat(stock).isEqualTo(new Stock("KB-104", "PUN", fulfilled ? 2 : 8, 0, fulfilled ? 6 : 0, 2));
            assertThat(store.events()).singleElement().extracting(OutboxEvent::status)
                .isEqualTo(fulfilled ? "PROCESSED" : "CANCELLED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTransfersConserveStockAndRejectExcessRequests() throws Exception {
        List<Boolean> results = concurrently(12, () -> {
            try {
                inventory.transfer(new TransferInput("KB-104", "PUN", "BLR", 2));
                return true;
            } catch (ApiProblem error) {
                assertThat(error.code).isEqualTo("INSUFFICIENT_STOCK");
                return false;
            }
        });
        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(4);
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(new Stock("KB-104", "PUN", 0, 0, 0, 4));
        assertThat(store.stock("KB-104", "BLR")).isEqualTo(new Stock("KB-104", "BLR", 10, 0, 0, 4));
        assertThat(new JdbcTemplate(db).queryForObject("""
            SELECT COUNT(*) FROM inventory_movements WHERE movement_type IN ('TRANSFER_OUT', 'TRANSFER_IN')
            """, Integer.class)).isEqualTo(8);
        assertThat(new InventoryAuditService(store).reconcile()).allSatisfy(line -> {
            assertThat(line.difference()).isZero();
            assertThat(line.overallIntegrityStatus()).isEqualTo("VALID");
        });
    }

    @Test
    void catalogCreatesAllStockRowsAndHonorsNewWarehousePriority() {
        inventory.addProduct(new ProductInput("T-123", "Test product", "Test"));
        inventory.addWarehouse(new WarehouseInput("NEW", "Test warehouse", "Chandigarh", 1));
        assertThat(store.stockForSku("T-123")).hasSize(4);
        assertThat(store.inventory()).hasSize(24);

        inventory.restock(new RestockInput("T-123", "PUN", 3));
        inventory.restock(new RestockInput("T-123", "NEW", 2));
        assertThat(inventory.place(order("T-123", 3)).order().allocations()).containsExactly(
            new Allocation("T-123", "NEW", 2), new Allocation("T-123", "PUN", 1));
    }

    @Test
    void productNetworkAvailabilityTracksWarehouseReservationsAndGlobalStockStatus() {
        inventory.addProduct(new ProductInput("IPH-17", "iPhone 17", "Phones"));
        inventory.restock(new RestockInput("IPH-17", "PUN", 50));
        inventory.restock(new RestockInput("IPH-17", "DEL", 30));
        inventory.restock(new RestockInput("IPH-17", "BLR", 20));
        assertThat(store.stockForSku("IPH-17")).hasSize(3);

        Order first = inventory.place(new OrderInput("Buyer", UUID.randomUUID(),
            List.of(new OrderLine("IPH-17", 50)), "PUN")).order();
        assertThat(first.allocations()).containsExactly(new Allocation("IPH-17", "PUN", 50));
        ProductInventory halfAvailable = store.productInventory().stream()
            .filter(product -> product.sku().equals("IPH-17")).findFirst().orElseThrow();
        assertThat(halfAvailable.totalStock()).isEqualTo(100);
        assertThat(halfAvailable.available()).isEqualTo(50);
        assertThat(halfAvailable.reserved()).isEqualTo(50);
        assertThat(halfAvailable.damaged()).isZero();
        assertThat(halfAvailable.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(halfAvailable.warehouses()).extracting(WarehouseBalance::warehouseCode,
            WarehouseBalance::available, WarehouseBalance::status).containsExactly(
                tuple("PUN", 0L, AvailabilityStatus.OUT_OF_STOCK),
                tuple("DEL", 30L, AvailabilityStatus.AVAILABLE),
                tuple("BLR", 20L, AvailabilityStatus.AVAILABLE));

        Order second = inventory.place(new OrderInput("Buyer", UUID.randomUUID(),
            List.of(new OrderLine("IPH-17", 50)), "PUN")).order();
        assertThat(second.allocations()).containsExactlyInAnyOrder(
            new Allocation("IPH-17", "DEL", 30), new Allocation("IPH-17", "BLR", 20));
        ProductInventory fullyReserved = store.productInventory().stream()
            .filter(product -> product.sku().equals("IPH-17")).findFirst().orElseThrow();
        assertThat(fullyReserved.totalStock()).isEqualTo(100);
        assertThat(fullyReserved.available()).isZero();
        assertThat(fullyReserved.reserved()).isEqualTo(100);
        assertThat(fullyReserved.status()).isEqualTo(AvailabilityStatus.OUT_OF_STOCK);
        assertThat(fullyReserved.warehouses()).allSatisfy(warehouse -> {
            assertThat(warehouse.available()).isZero();
            assertThat(warehouse.status()).isEqualTo(AvailabilityStatus.OUT_OF_STOCK);
        });
        assertThat(new InventoryAuditService(store).reconcile())
            .allMatch(line -> line.overallIntegrityStatus().equals("VALID"));
    }

    @Test
    void preferredWarehouseIsTriedFirstThenFallsBackAndConcurrentOrdersCannotOversubscribeNetworkStock()
        throws Exception {
        inventory.addProduct(new ProductInput("IPH-17", "iPhone 17", "Phones"));
        inventory.restock(new RestockInput("IPH-17", "PUN", 50));
        inventory.restock(new RestockInput("IPH-17", "DEL", 30));
        inventory.restock(new RestockInput("IPH-17", "BLR", 20));

        Order preferred = inventory.place(new OrderInput("Buyer", UUID.randomUUID(),
            List.of(new OrderLine("IPH-17", 20)), "BLR")).order();
        assertThat(preferred.allocations()).containsExactly(new Allocation("IPH-17", "BLR", 20));

        List<Boolean> outcomes = concurrently(20, () -> {
            try {
                inventory.place(new OrderInput("Concurrent buyer", UUID.randomUUID(),
                    List.of(new OrderLine("IPH-17", 5)), "PUN"));
                return true;
            } catch (ApiProblem error) {
                assertThat(error.code).isEqualTo("INSUFFICIENT_STOCK");
                return false;
            }
        });

        assertThat(outcomes).filteredOn(Boolean::booleanValue).hasSize(16);
        assertThat(store.stockForSku("IPH-17")).allSatisfy(stock -> {
            assertThat(stock.available()).isGreaterThanOrEqualTo(0);
            assertThat(stock.reserved()).isGreaterThanOrEqualTo(0);
        });
        ProductInventory network = store.productInventory().stream()
            .filter(product -> product.sku().equals("IPH-17")).findFirst().orElseThrow();
        assertThat(network.available()).isZero();
        assertThat(network.reserved()).isEqualTo(100);
        assertThat(network.status()).isEqualTo(AvailabilityStatus.OUT_OF_STOCK);
        assertThat(new InventoryAuditService(store).reconcile())
            .allMatch(line -> line.overallIntegrityStatus().equals("VALID"));
    }

    @Test
    void liveRaceUsesRealConflictsAndLeavesUnallocatableRemainder() {
        RaceResult result = new RaceDemo().run(new RaceInput(24, 11, 3));
        assertThat(result.verified()).isTrue();
        assertThat(result.accepted()).isEqualTo(3);
        assertThat(result.rejected()).isEqualTo(21);
        assertThat(result.optimisticConflicts()).isGreaterThanOrEqualTo(23);
        assertThat(result.shipped()).isEqualTo(9);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.reserved()).isZero();
    }

    @Test
    void stockOperationsWriteAtomicMovementsAndReconcileAcrossBuckets() {
        int openingEntries = new JdbcTemplate(db).queryForObject(
            "SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 'INITIAL_STOCK'", Integer.class);
        new InventoryLedgerBootstrap(store, new JdbcTransactionManager(db)).initialize();
        assertThat(new JdbcTemplate(db).queryForObject(
            "SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 'INITIAL_STOCK'", Integer.class))
            .isEqualTo(openingEntries);

        UUID restockKey = UUID.randomUUID();
        inventory.restock(new RestockInput("KB-104", "PUN", 4, restockKey));
        inventory.restock(new RestockInput("KB-104", "PUN", 4, restockKey));
        assertThat(store.stock("KB-104", "PUN").available()).isEqualTo(12);
        assertThat(new JdbcTemplate(db).queryForObject("""
            SELECT COUNT(*) FROM inventory_movements
            WHERE operation_id = ? AND movement_type = 'RESTOCK'
            """, Integer.class, restockKey)).isEqualTo(1);
        assertThatThrownBy(() -> inventory.restock(new RestockInput("KB-104", "PUN", 5, restockKey)))
            .isInstanceOfSatisfying(ApiProblem.class, error -> assertThat(error.code).isEqualTo("IDEMPOTENCY_MISMATCH"));

        Order order = inventory.place(order("KB-104", 3)).order();
        assertThat(store.stock("KB-104", "PUN").reserved()).isEqualTo(3);
        assertThat(inventory.cancel(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        inventory.transfer(new TransferInput("KB-104", "PUN", "BLR", 2, UUID.randomUUID()));
        inventory.recordDamage(new DamagedStockInput("KB-104", "PUN", 1, "Crushed carton", UUID.randomUUID()));
        inventory.writeOff(new WriteOffInput("KB-104", "PUN", 1, "Unrecoverable damage", UUID.randomUUID()));
        inventory.adjust(new StockAdjustmentInput("KB-104", "PUN", StockBucket.AVAILABLE, 2L,
            "Cycle count correction", UUID.randomUUID()));
        inventory.setThreshold(new ThresholdInput("KB-104", "PUN", 15));

        assertThat(new InventoryAuditService(store).reconcile()).allSatisfy(line -> {
            assertThat(line.difference()).isZero();
            assertThat(line.overallIntegrityStatus()).isEqualTo("VALID");
        });
        assertThat(store.lowStockAlerts()).anySatisfy(alert -> {
            assertThat(alert.sku()).isEqualTo("KB-104");
            assertThat(alert.warehouseCode()).isEqualTo("PUN");
            assertThat(alert.replenishmentUnits()).isEqualTo(4);
        });
        List<Stock> finalStocks = store.inventory();
        Overview overview = store.overview();
        assertThat(overview.damaged()).isZero();
        assertThat(overview.physical()).isEqualTo(finalStocks.stream()
            .mapToLong(stock -> stock.available() + stock.reserved() + stock.damaged()).sum());
    }

    @Test
    void overviewAndAlertsIncludeInventoryWithoutThresholdAndHonorCustomThresholds() {
        var jdbc = new JdbcTemplate(db);
        jdbc.update("DELETE FROM stock_thresholds WHERE sku = 'KB-104' AND warehouse_code = 'BLR'");
        store.setThreshold("KB-104", "PUN", 10);

        List<Stock> stocks = store.inventory();
        Overview overview = store.overview();
        assertThat(overview.stockRows()).isEqualTo(stocks.size());
        assertThat(overview.available()).isEqualTo(stocks.stream().mapToLong(Stock::available).sum());
        assertThat(overview.reserved()).isEqualTo(stocks.stream().mapToLong(Stock::reserved).sum());
        assertThat(overview.damaged()).isEqualTo(stocks.stream().mapToLong(Stock::damaged).sum());
        assertThat(overview.shipped()).isEqualTo(stocks.stream().mapToLong(Stock::shipped).sum());
        assertThat(overview.writtenOff()).isEqualTo(stocks.stream().mapToLong(Stock::writtenOff).sum());
        assertThat(overview.physical()).isEqualTo(stocks.stream()
            .mapToLong(stock -> stock.available() + stock.reserved() + stock.damaged()).sum());
        assertThat(overview.lowStockRows()).isEqualTo(jdbc.queryForObject("""
            SELECT COUNT(*) FROM inventory i LEFT JOIN stock_thresholds t
              ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.available <= COALESCE(t.minimum_available, 5)
            """, Integer.class));
        assertThat(store.lowStockAlerts()).anySatisfy(alert -> {
            assertThat(alert.sku()).isEqualTo("KB-104");
            assertThat(alert.warehouseCode()).isEqualTo("BLR");
            assertThat(alert.minimumAvailable()).isEqualTo(5);
        });
        assertThat(store.lowStockAlerts()).anySatisfy(alert -> {
            assertThat(alert.sku()).isEqualTo("KB-104");
            assertThat(alert.warehouseCode()).isEqualTo("PUN");
            assertThat(alert.minimumAvailable()).isEqualTo(10);
        });
    }

    @Test
    void manualAdjustmentsRejectReservedShippedAndWrittenOffBuckets() {
        Stock before = store.stock("KB-104", "PUN");
        int movementsBefore = new JdbcTemplate(db).queryForObject(
            "SELECT COUNT(*) FROM inventory_movements", Integer.class);
        for (StockBucket bucket : List.of(StockBucket.RESERVED, StockBucket.SHIPPED, StockBucket.WRITTEN_OFF)) {
            assertThatThrownBy(() -> inventory.adjust(new StockAdjustmentInput("KB-104", "PUN", bucket, 1L,
                "Incorrect direct bucket adjustment", UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiProblem.class, error -> assertThat(error.status.value()).isEqualTo(400));
        }
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(before);
        assertThat(new JdbcTemplate(db).queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class))
            .isEqualTo(movementsBefore);

        Stock adjusted = inventory.adjust(new StockAdjustmentInput("KB-104", "PUN", StockBucket.DAMAGED, 1L,
            "Damaged stock count correction", UUID.randomUUID()));
        assertThat(adjusted.damaged()).isEqualTo(before.damaged() + 1);
        assertThat(new InventoryAuditService(store).reconcile()).allMatch(line -> line.difference() == 0);
    }

    @Test
    void reservationOperationIntegrityHandlesMultipleProductsAndWarehouseAllocations() {
        OrderInput input = new OrderInput("Multi warehouse buyer", UUID.randomUUID(), List.of(
            new OrderLine("KB-104", 10), new OrderLine("CB-305", 4)));
        Order order = inventory.place(input).order();
        assertThat(order.allocations()).hasSize(3);
        assertThat(new InventoryAuditService(store).reconcile())
            .allMatch(line -> line.overallIntegrityStatus().equals("VALID"));
    }

    @Test
    void reconciliationDetectsIncorrectPreviousBalanceInMovementChain() {
        Stock stock = store.stock("KB-104", "PUN");
        UUID operationId = createTestOperation("STOCK_ADJUSTMENT");
        long incorrectPrevious = stock.available() + 7;
        createTestMovement(UUID.randomUUID(), operationId, "KB-104", "PUN", MovementType.STOCK_ADJUSTMENT,
            StockBucket.AVAILABLE, 1, incorrectPrevious, incorrectPrevious + 1, InventoryService.now().plusDays(1), null, null);

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.discontinuityCount()).isPositive();
        assertThat(line.ledgerChainStatus()).isEqualTo("DISCONTINUITY");
        assertThat(line.overallIntegrityStatus()).isEqualTo("INVALID");
    }

    @Test
    void reconciliationDetectsInvalidNewBalanceArithmetic() {
        new JdbcTemplate(db).execute("ALTER TABLE inventory_movements DROP CONSTRAINT movement_balance_ck");
        Stock stock = store.stock("KB-104", "PUN");
        UUID operationId = createTestOperation("RESTOCK");
        createTestMovement(UUID.randomUUID(), operationId, "KB-104", "PUN", MovementType.RESTOCK,
            StockBucket.AVAILABLE, 1, stock.available(), stock.available() + 2,
            InventoryService.now().plusDays(1), null, null);

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.invalidMovementCount()).isEqualTo(1);
        assertThat(line.ledgerChainStatus()).isEqualTo("INVALID_MOVEMENT");
        assertThat(line.bucketBalanceMismatch()).isTrue();
    }

    @Test
    void reconciliationDetectsTransferMissingItsCounterpart() {
        Stock stock = store.stock("KB-104", "PUN");
        UUID operationId = createTestOperation("TRANSFER");
        createTestMovement(UUID.randomUUID(), operationId, "KB-104", "PUN", MovementType.TRANSFER_OUT,
            StockBucket.AVAILABLE, -1, stock.available(), stock.available() - 1,
            InventoryService.now().plusDays(1), "PUN", "DEL");

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.unbalancedTransferCount()).isEqualTo(1);
        assertThat(line.unbalancedOperationCount()).isZero();
        assertThat(line.overallIntegrityStatus()).isEqualTo("INVALID");
    }

    @Test
    void reconciliationDetectsReservationWithOnlyOneBucketMovement() {
        Stock stock = store.stock("KB-104", "PUN");
        UUID operationId = createTestOperation("ORDER_RESERVATION");
        createTestMovement(UUID.randomUUID(), operationId, "KB-104", "PUN", MovementType.RESERVATION,
            StockBucket.AVAILABLE, -1, stock.available(), stock.available() - 1,
            InventoryService.now().plusDays(1), null, null);

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.unbalancedOperationCount()).isEqualTo(1);
        assertThat(line.overallIntegrityStatus()).isEqualTo("INVALID");
    }

    @Test
    void reconciliationDetectsMovementWithoutAnOperation() {
        var jdbc = new JdbcTemplate(db);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        Stock stock = store.stock("KB-104", "PUN");
        UUID missingOperationId = UUID.randomUUID();
        createTestMovement(UUID.randomUUID(), missingOperationId, "KB-104", "PUN", MovementType.RESTOCK,
            StockBucket.AVAILABLE, 1, stock.available(), stock.available() + 1,
            InventoryService.now().plusDays(1), null, null);
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.missingOperationCount()).isEqualTo(1);
        assertThat(line.overallIntegrityStatus()).isEqualTo("INVALID");
    }

    @Test
    void reconciliationDetectsCurrentStockThatDiffersFromLedger() {
        new JdbcTemplate(db).update("UPDATE inventory SET available = available + 1, version = version + 1 "
            + "WHERE sku = 'KB-104' AND warehouse_code = 'PUN'");

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.bucketBalanceMismatch()).isTrue();
        assertThat(line.difference()).isEqualTo(1);
        assertThat(line.ledgerChainStatus()).isEqualTo("BALANCE_MISMATCH");
    }

    @Test
    void reconciliationReportsLedgerBucketWithoutInventoryRow() {
        Stock stock = store.stock("CB-305", "DEL");
        new JdbcTemplate(db).update("DELETE FROM inventory WHERE sku = 'CB-305' AND warehouse_code = 'DEL'");
        UUID operationId = createTestOperation("RESTOCK");
        createTestMovement(UUID.randomUUID(), operationId, "CB-305", "DEL", MovementType.RESTOCK,
            StockBucket.AVAILABLE, 2, stock.available(), stock.available() + 2,
            InventoryService.now().plusDays(1), null, null);

        ReconciliationLine line = reconciliationLine("CB-305", "DEL", StockBucket.AVAILABLE);
        assertThat(line.expectedQuantity()).isEqualTo(stock.available() + 2);
        assertThat(line.actualQuantity()).isZero();
        assertThat(line.bucketBalanceMismatch()).isTrue();
        assertThat(line.overallIntegrityStatus()).isEqualTo("INVALID");
    }

    @Test
    void equalTimestampsUseMovementIdToBuildDeterministicChain() {
        Stock stock = store.stock("KB-104", "PUN");
        OffsetDateTime sameTime = InventoryService.now().plusDays(1);
        UUID firstOperation = createTestOperation("STOCK_ADJUSTMENT");
        UUID secondOperation = createTestOperation("STOCK_ADJUSTMENT");
        InventoryMovement first = new InventoryMovement(new UUID(0, 101), "KB-104", "PUN",
            MovementType.STOCK_ADJUSTMENT, StockBucket.AVAILABLE, 1, stock.available(), stock.available() + 1,
            null, null, null, "Same-time integrity fixture A", "test", sameTime, firstOperation);
        InventoryMovement second = new InventoryMovement(new UUID(0, 102), "KB-104", "PUN",
            MovementType.STOCK_ADJUSTMENT, StockBucket.AVAILABLE, 1, stock.available() + 1, stock.available() + 2,
            null, null, null, "Same-time integrity fixture B", "test", sameTime, secondOperation);
        store.insertMovement(second);
        store.insertMovement(first);
        new JdbcTemplate(db).update("UPDATE inventory SET available = available + 2, version = version + 1 "
            + "WHERE sku = 'KB-104' AND warehouse_code = 'PUN'");

        ReconciliationLine line = reconciliationLine("KB-104", "PUN", StockBucket.AVAILABLE);
        assertThat(line.ledgerChainStatus()).isEqualTo("VALID");
        assertThat(line.discontinuityCount()).isZero();
        assertThat(line.expectedQuantity()).isEqualTo(line.actualQuantity());
        assertThat(line.overallIntegrityStatus()).isEqualTo("VALID");
    }

    @Test
    void fulfillmentAndReturnsMoveQuantitiesAndWriteLinkedLedgerRows() {
        Order order = inventory.place(order("KB-104", 2)).order();
        assertThat(inventory.fulfill(order.id())).isTrue();
        UUID requestKey = UUID.randomUUID();
        inventory.returnStock(new ReturnInput(order.id(), "KB-104", "PUN", 1,
            "Customer returned unopened unit", requestKey));
        inventory.returnStock(new ReturnInput(order.id(), "KB-104", "PUN", 1,
            "Customer returned unopened unit", requestKey));
        assertThatThrownBy(() -> inventory.returnStock(new ReturnInput(order.id(), "KB-104", "PUN", 2,
            "Excess return", UUID.randomUUID()))).isInstanceOfSatisfying(ApiProblem.class,
            error -> assertThat(error.code).isEqualTo("RETURN_EXCEEDS_SHIPPED"));

        assertThat(store.stock("KB-104", "PUN")).isEqualTo(new Stock("KB-104", "PUN", 7, 0, 0, 1, 0, 5, 3));
        assertThat(new JdbcTemplate(db).queryForObject("""
            SELECT COUNT(*) FROM inventory_movements WHERE reference_order_id = ?
              AND movement_type IN ('RESERVATION', 'FULFILLMENT', 'RETURN')
            """, Integer.class, order.id())).isEqualTo(6);
        assertThat(new InventoryAuditService(store).reconcile()).allMatch(line -> line.difference() == 0);
    }

    @Test
    void movementHistorySupportsProductWarehouseTypeDateAndOrderFilters() {
        Order order = inventory.place(order("KB-104", 1)).order();
        var rows = new InventoryAuditService(store).movements("keyboard", "PUN", MovementType.RESERVATION,
            null, null, order.id(), 20, 0);
        assertThat(rows).hasSize(2).allSatisfy(movement -> {
            assertThat(movement.productId()).isEqualTo("KB-104");
            assertThat(movement.warehouseId()).isEqualTo("PUN");
            assertThat(movement.referenceOrderId()).isEqualTo(order.id());
            assertThat(movement.operationId()).isNotNull();
            assertThat(movement.reason()).isNotBlank();
        });
    }

    @Test
    void movementPaginationUsesMonotonicSequenceForEqualTimestamps() {
        OffsetDateTime timestamp = InventoryService.now().plusDays(1);
        List<UUID> inserted = List.of(new UUID(0, 303), new UUID(0, 301), new UUID(0, 302));
        for (UUID movementId : inserted) {
            UUID operationId = createTestOperation("STOCK_ADJUSTMENT");
            createTestMovement(movementId, operationId, "KB-104", "PUN", MovementType.STOCK_ADJUSTMENT,
                StockBucket.AVAILABLE, 1, 0, 1, timestamp, null, null);
        }

        InventoryAuditService audit = new InventoryAuditService(store);
        List<InventoryMovement> firstPage = audit.movements("KB-104", "PUN", MovementType.STOCK_ADJUSTMENT,
            timestamp.minusSeconds(1), timestamp.plusSeconds(1), null, 2, 0);
        List<InventoryMovement> secondPage = audit.movements("KB-104", "PUN", MovementType.STOCK_ADJUSTMENT,
            timestamp.minusSeconds(1), timestamp.plusSeconds(1), null, 2, 2);
        assertThat(firstPage).extracting(InventoryMovement::movementId)
            .containsExactly(inserted.get(2), inserted.get(1));
        assertThat(secondPage).extracting(InventoryMovement::movementId).containsExactly(inserted.get(0));
        assertThat(new JdbcTemplate(db).queryForObject("""
            SELECT COUNT(DISTINCT movement_sequence) FROM inventory_movements
            """, Integer.class)).isEqualTo(new JdbcTemplate(db).queryForObject(
                "SELECT COUNT(*) FROM inventory_movements", Integer.class));
    }

    @Test
    void flywayCreatesUniqueMovementSequenceForLedgerHistory() {
        var jdbc = new JdbcTemplate(db);
        List<InventoryMovement> before = store.allMovementsForIntegrity();
        int countBefore = before.size();
        assertThat(store.allMovementsForIntegrity()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class)).isEqualTo(countBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT movement_sequence) FROM inventory_movements", Integer.class))
            .isEqualTo(countBefore);
        long sequenceBeforeInsert = jdbc.queryForObject(
            "SELECT MAX(movement_sequence) FROM inventory_movements", Long.class);
        Stock stock = store.stock("CB-305", "DEL");
        UUID operationId = createTestOperation("STOCK_ADJUSTMENT");
        UUID movementId = UUID.randomUUID();
        createTestMovement(movementId, operationId, "CB-305", "DEL", MovementType.STOCK_ADJUSTMENT,
            StockBucket.AVAILABLE, 1, stock.available(), stock.available() + 1,
            InventoryService.now().plusDays(2), null, null);
        assertThat(jdbc.queryForObject("SELECT movement_sequence FROM inventory_movements WHERE movement_id = ?",
            Long.class, movementId)).isGreaterThan(sequenceBeforeInsert);
    }

    @Test
    void flywayCanBaselineAndReplayTheExistingSchemaWithoutDuplicatingBusinessData() {
        inventory.restock(new RestockInput("KB-104", "PUN", 2));
        inventory.place(order("KB-104", 1));
        var jdbc = new JdbcTemplate(db);
        Stock stockBefore = store.stock("KB-104", "PUN");
        int orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class);
        int movementCount = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class);
        int operationCount = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_operations", Integer.class);
        Flyway.configure().dataSource(db).locations("classpath:db/migration")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        new InventoryLedgerBootstrap(store, new JdbcTransactionManager(db)).initialize();

        assertThat(store.stock("KB-104", "PUN")).isEqualTo(stockBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class)).isEqualTo(orderCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class)).isEqualTo(movementCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_operations", Integer.class)).isEqualTo(operationCount);
        assertThat(new InventoryAuditService(store).reconcile())
            .allMatch(line -> line.overallIntegrityStatus().equals("VALID"));
    }

    @Test
    void existingDatabaseReceivesOpeningLedgerEntriesWithoutChangingBalances() throws Exception {
        var legacy = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc = new JdbcTemplate(legacy);
            jdbc.execute("CREATE TABLE products (sku VARCHAR(24) PRIMARY KEY, name VARCHAR(80) NOT NULL, category VARCHAR(40) NOT NULL)");
            jdbc.execute("CREATE TABLE warehouses (code VARCHAR(16) PRIMARY KEY, name VARCHAR(80) NOT NULL, city VARCHAR(60) NOT NULL, priority INTEGER NOT NULL)");
            jdbc.execute("""
                CREATE TABLE inventory (sku VARCHAR(24) NOT NULL, warehouse_code VARCHAR(16) NOT NULL,
                    available BIGINT NOT NULL, reserved BIGINT NOT NULL, shipped BIGINT NOT NULL, version BIGINT NOT NULL,
                    PRIMARY KEY (sku, warehouse_code))
                """);
            jdbc.update("INSERT INTO products VALUES ('OLD-1', 'Legacy product', 'Legacy')");
            jdbc.update("INSERT INTO warehouses VALUES ('OLD-W', 'Legacy warehouse', 'Legacy City', 1)");
            jdbc.update("INSERT INTO inventory VALUES ('OLD-1', 'OLD-W', 10, 2, 3, 7)");

            Flyway.configure().dataSource(legacy).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            var legacyStore = new Store(jdbc);
            new InventoryLedgerBootstrap(legacyStore, new JdbcTransactionManager(legacy)).initialize();

            assertThat(legacyStore.stock("OLD-1", "OLD-W")).isEqualTo(new Stock("OLD-1", "OLD-W", 10, 2, 0, 3, 0, 5, 7));
            assertThat(new InventoryAuditService(legacyStore).reconcile()).allMatch(line -> line.difference() == 0);
        } finally {
            legacy.shutdown();
        }
    }

    @Test
    void ledgerInsertFailureRollsBackStockAndIdempotencyRecord() {
        var jdbc = new JdbcTemplate(db);
        jdbc.execute("ALTER TABLE inventory_movements ADD CONSTRAINT reject_restock CHECK (movement_type <> 'RESTOCK')");
        Stock before = store.stock("KB-104", "PUN");
        UUID requestKey = UUID.randomUUID();
        assertThatThrownBy(() -> inventory.restock(new RestockInput("KB-104", "PUN", 2, requestKey)))
            .isInstanceOf(RuntimeException.class).isNotInstanceOf(ApiProblem.class);
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(before);

        jdbc.execute("ALTER TABLE inventory_movements DROP CONSTRAINT reject_restock");
        assertThat(inventory.restock(new RestockInput("KB-104", "PUN", 2, requestKey)).available())
            .isEqualTo(before.available() + 2);
    }

    @Test
    void naiveDatabaseAndOptimisticModesCompareOneHundredBuyersForTenUnits() {
        RaceResult result = new RaceDemo().run(new RaceInput(100, 10, 1, 2, 10, 60, "COMPARE"));
        assertThat(result.optimistic().attempts()).isEqualTo(100);
        assertThat(result.optimistic().accepted() + result.optimistic().rejected()).isEqualTo(100);
        assertThat(result.unsafe().accepted()).isEqualTo(100);
        assertThat(result.unsafe().oversold()).isEqualTo(90);
        assertThat(result.unsafe().correct()).isFalse();
        assertThat(result.optimistic().accepted()).isEqualTo(10);
        assertThat(result.optimistic().oversold()).isZero();
        assertThat(result.optimistic().finalAvailable()).isZero();
        assertThat(result.optimistic().correct()).isTrue();
        assertThat(result.pessimistic().accepted()).isEqualTo(10);
        assertThat(result.pessimistic().oversold()).isZero();
        assertThat(result.pessimistic().correct()).isTrue();
        assertThat(result.pessimistic().lockWaitMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.pessimistic().databaseTimeMs()).isPositive();
    }

    @Test
    void oneThousandBuyersCannotReserveMoreThanOneHundredUnits() {
        RaceResult result = new RaceDemo().run(new RaceInput(1_000, 100, 1, 4, 10, 120, "COMPARE"));
        assertThat(result.optimistic().attempts()).isEqualTo(1_000);
        assertThat(result.optimistic().accepted() + result.optimistic().rejected()).isEqualTo(1_000);
        assertThat(result.unsafe().oversold()).isPositive();
        assertThat(result.optimistic().accepted()).isEqualTo(100);
        assertThat(result.optimistic().oversold()).isZero();
        assertThat(result.optimistic().finalAvailable()).isZero();
        assertThat(result.optimistic().correct()).isTrue();
        assertThat(result.pessimistic().accepted()).isEqualTo(100);
        assertThat(result.pessimistic().oversold()).isZero();
        assertThat(result.pessimistic().finalAvailable()).isZero();
        assertThat(result.pessimistic().correct()).isTrue();
        assertThat(result.pessimistic().deadlocks()).isZero();
    }

    @Test
    void largeSuccessfulRunFulfillsMoreThanOneWorkerBatchAcrossWarehouses() {
        RaceResult result = new RaceDemo().run(new RaceInput(1_000, 1_000, 1, 8, 32, 120, "OPTIMISTIC"));
        assertThat(result.accepted()).isEqualTo(1_000);
        assertThat(result.rejected()).isZero();
        assertThat(result.reserved()).isZero();
        assertThat(result.shipped()).isEqualTo(1_000);
        assertThat(result.remaining()).isZero();
        assertThat(result.verified()).isTrue();
    }

    @Test
    void repeatedLoadsPreserveOptimisticInventoryAcrossWorkerAndWarehouseCounts() {
        var configurations = List.of(
            new RaceInput(10, 3, 1, 1, 1, 30, "OPTIMISTIC"),
            new RaceInput(50, 17, 2, 3, 7, 60, "OPTIMISTIC"),
            new RaceInput(250, 100, 1, 8, 32, 90, "OPTIMISTIC"),
            new RaceInput(500, 500, 1, 16, 64, 120, "OPTIMISTIC")
        );
        for (RaceInput configuration : configurations) {
            RaceResult result = new RaceDemo().run(configuration);
            assertThat(result.accepted() * (long) configuration.quantity())
                .isLessThanOrEqualTo(configuration.stock());
            assertThat(result.remaining() + result.shipped() + result.reserved())
                .isEqualTo((long) configuration.stock());
            assertThat(result.verified()).isTrue();
        }
    }

    @Test
    void zeroAndNegativeBuyerQuantitiesFailBeanValidation() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(new RaceInput(10, 10, 0, 1, 1, 30, "COMPARE"))).isNotEmpty();
        assertThat(validator.validate(new RaceInput(10, 10, -1, 1, 1, 30, "COMPARE"))).isNotEmpty();
    }

    @Test
    void repeatedDatabaseLockContentionExhaustsRetriesWithoutChangingStock() throws Exception {
        new JdbcTemplate(db).execute("SET DEFAULT_LOCK_TIMEOUT 25");
        Stock before = store.stock("KB-104", "PUN");
        InventoryService limitedRetries = new InventoryService(store, new JdbcTransactionManager(db), Duration.ZERO,
            new InventoryService.RetrySettings(2, Duration.ofNanos(1), Duration.ofNanos(2)));
        try (var lock = db.getConnection()) {
            lock.setAutoCommit(false);
            try (var result = lock.createStatement().executeQuery(
                "SELECT available FROM inventory WHERE sku = 'KB-104' AND warehouse_code = 'PUN' FOR UPDATE")) {
                assertThat(result.next()).isTrue();
            }
            assertThatThrownBy(() -> limitedRetries.restock(new RestockInput("KB-104", "PUN", 1)))
                .isInstanceOfSatisfying(ApiProblem.class, error -> assertThat(error.code).isEqualTo("BUSY"));
        }
        assertThat(store.stock("KB-104", "PUN")).isEqualTo(before);
    }

    @Test
    void unavailableDatabaseIsReportedSeparatelyFromRetryExhaustion() {
        PlatformTransactionManager unavailableManager = mock(PlatformTransactionManager.class);
        when(unavailableManager.getTransaction(any()))
            .thenThrow(new DataAccessResourceFailureException("Database connection pool exhausted"));
        InventoryService unavailableInventory = new InventoryService(store, unavailableManager, Duration.ZERO);

        assertThatThrownBy(() -> unavailableInventory.restock(new RestockInput("KB-104", "PUN", 1)))
            .isInstanceOfSatisfying(ApiProblem.class,
                error -> assertThat(error.code).isEqualTo("DATABASE_UNAVAILABLE"));
    }

    @Test
    void asynchronousSimulationPublishesProgressAndComparisonResults() throws Exception {
        var lab = new RaceDemo();
        try {
            RaceJob job = lab.start(new RaceInput(10, 10, 1, 2, 4, 30, "COMPARE"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            RaceProgress progress;
            do {
                progress = lab.progress(job.id());
                if (progress.status().equals("COMPLETED") || progress.status().equals("FAILED")) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);

            assertThat(progress.status()).isEqualTo("COMPLETED");
            assertThat(progress.completed()).isEqualTo(30);
            assertThat(progress.result().unsafe()).isNotNull();
            assertThat(progress.result().optimistic()).isNotNull();
            assertThat(progress.result().pessimistic()).isNotNull();
        } finally {
            lab.destroy();
        }
    }

    @Test
    void databaseStrategiesUseIsolatedFreshStockAndPessimisticSplitsAcrossWarehouses() {
        List<Stock> productionBefore = store.inventory();
        RaceResult comparison = new RaceDemo().run(new RaceInput(100, 30, 1, 3, 10, 60, "COMPARE"));
        assertThat(comparison.rounds()).singleElement().satisfies(round -> {
            assertThat(round.strategies()).extracting(RaceModeResult::mode)
                .containsExactly("NAIVE", "OPTIMISTIC", "PESSIMISTIC");
            assertThat(round.strategies()).allSatisfy(strategy -> {
                assertThat(strategy.attempts()).isEqualTo(100);
                assertThat(strategy.isolationLevel()).isEqualTo("READ_COMMITTED");
                if (!strategy.mode().equals("NAIVE")) assertThat(strategy.finalAvailable()).isZero();
            });
        });
        assertThat(comparison.optimistic().correct()).isTrue();
        assertThat(comparison.pessimistic().correct()).isTrue();
        assertThat(comparison.unsafe().lostUpdates()).isPositive();
        assertThat(comparison.unsafe().correct()).isFalse();
        assertThat(store.inventory()).isEqualTo(productionBefore);

        RaceInput input = new RaceInput(10, 10, 5, 3, 2, 30, "PESSIMISTIC");
        try (RaceSimulationDatabase isolated = new RaceSimulationDatabase(input)) {
            assertThat(isolated.store.stockForSkuForUpdate("RACE-01").stream()
                .map(Stock::warehouseCode).toList()).containsExactly("W01", "W02", "W03");
            var order = new OrderInput("Split buyer", UUID.randomUUID(), List.of(new OrderLine("RACE-01", 5)));
            assertThat(new PessimisticDatabaseRaceStrategy().reserve(isolated, order, () -> {}, new RaceCounters()))
                .isTrue();
            assertThat(isolated.store.orders(null, 10, 0)).singleElement().extracting(Order::allocations)
                .asList().hasSize(2);
            assertThat(new InventoryAuditService(isolated.store).reconcile())
                .allMatch(line -> line.overallIntegrityStatus().equals("VALID"));
        }
    }

    @Test
    void benchmarkRepetitionsCreateIndependentStrategyRounds() {
        RaceResult result = new RaceDemo().run(new RaceInput(20, 10, 1, 2, 5, 30, "PESSIMISTIC", 2));
        assertThat(result.rounds()).hasSize(2);
        assertThat(result.rounds()).allSatisfy(round -> {
            assertThat(round.strategies()).singleElement().satisfies(strategy -> {
                assertThat(strategy.mode()).isEqualTo("PESSIMISTIC");
                assertThat(strategy.accepted()).isEqualTo(10);
                assertThat(strategy.correct()).isTrue();
            });
        });
    }

    @Test
    void pessimisticSimulationRollsBackStockOrderAndLedgerWhenOutboxInsertFails() {
        RaceInput input = new RaceInput(10, 10, 1, 2, 2, 30, "PESSIMISTIC");
        try (RaceSimulationDatabase isolated = new RaceSimulationDatabase(input)) {
            List<Stock> before = isolated.store.stockForSku("RACE-01");
            int movementCount = isolated.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class);
            isolated.jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT reject_simulation_event CHECK (1 = 0)");
            var order = new OrderInput("Rollback buyer", UUID.randomUUID(), List.of(new OrderLine("RACE-01", 1)));
            assertThatThrownBy(() -> new PessimisticDatabaseRaceStrategy().reserve(
                isolated, order, () -> {}, new RaceCounters())).isInstanceOf(RuntimeException.class);
            assertThat(isolated.store.stockForSku("RACE-01")).isEqualTo(before);
            assertThat(isolated.jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class)).isZero();
            assertThat(isolated.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class))
                .isEqualTo(movementCount);
        }
    }

    @Test
    void pessimisticReservationRetriesDatabaseLockTimeoutAndThenCommits() throws Exception {
        RaceInput input = new RaceInput(10, 10, 1, 2, 2, 30, "PESSIMISTIC");
        try (RaceSimulationDatabase isolated = new RaceSimulationDatabase(input);
             var blocker = isolated.dataSource.getConnection()) {
            isolated.jdbc.execute("SET DEFAULT_LOCK_TIMEOUT 30");
            blocker.setAutoCommit(false);
            try (var lock = blocker.createStatement().executeQuery(
                "SELECT available FROM inventory WHERE sku = 'RACE-01' AND warehouse_code = 'W01' FOR UPDATE")) {
                assertThat(lock.next()).isTrue();
            }
            var counters = new RaceCounters();
            var pool = Executors.newSingleThreadExecutor();
            try {
                var order = new OrderInput("Retry buyer", UUID.randomUUID(), List.of(new OrderLine("RACE-01", 1)));
                Future<Boolean> result = pool.submit(() -> new PessimisticDatabaseRaceStrategy()
                    .reserve(isolated, order, () -> {}, counters));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                while (counters.retries.sum() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
                assertThat(counters.retries.sum()).isPositive();
                blocker.rollback();
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(isolated.store.stockForSku("RACE-01").stream()
                    .mapToLong(Stock::reserved).sum()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void cancellingLargeSimulationStopsItAndReleasesTheSimulationSlot() throws Exception {
        var lab = new RaceDemo();
        try {
            RaceJob cancelled = lab.start(new RaceInput(1_000, 1_000, 1, 1, 1, 300, "OPTIMISTIC"));
            assertThatThrownBy(() -> lab.start(new RaceInput(10, 10, 1, 1, 2, 30, "PESSIMISTIC")))
                .isInstanceOfSatisfying(ApiProblem.class, error -> assertThat(error.code).isEqualTo("BUSY"));
            lab.cancel(cancelled.id());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            RaceProgress progress;
            do {
                progress = lab.progress(cancelled.id());
                if (progress.status().equals("CANCELLED") || progress.status().equals("FAILED")) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);

            assertThat(progress.status()).isEqualTo("CANCELLED");
            RaceJob next = lab.start(new RaceInput(10, 10, 1, 1, 2, 30, "OPTIMISTIC"));
            long nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                progress = lab.progress(next.id());
                if (progress.status().equals("COMPLETED") || progress.status().equals("FAILED")) break;
                Thread.sleep(10);
            } while (System.nanoTime() < nextDeadline);
            assertThat(progress.status()).isEqualTo("COMPLETED");
        } finally {
            lab.destroy();
        }
    }

    private OrderInput order(String sku, int quantity) {
        return new OrderInput("Buyer", UUID.randomUUID(), List.of(new OrderLine(sku, quantity)));
    }

    private UUID createTestOperation(String type) {
        UUID operationId = UUID.randomUUID();
        store.insertOperation(operationId, null, type, "a".repeat(64), InventoryService.now());
        return operationId;
    }

    private void createTestMovement(UUID movementId, UUID operationId, String sku, String warehouseCode,
                                    MovementType type, StockBucket bucket, long change, long previous, long next,
                                    OffsetDateTime timestamp, String source, String destination) {
        store.insertMovement(new InventoryMovement(movementId, sku, warehouseCode, type, bucket, change,
            previous, next, null, source, destination, "Integrity test fixture", "test", timestamp, operationId));
    }

    private ReconciliationLine reconciliationLine(String sku, String warehouseCode, StockBucket bucket) {
        return new InventoryAuditService(store).reconcile().stream()
            .filter(line -> line.sku().equals(sku) && line.warehouseCode().equals(warehouseCode)
                && line.stockBucket() == bucket)
            .findFirst().orElseThrow();
    }

    private <T> List<T> concurrently(int size, Callable<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(size);
        var start = new CyclicBarrier(size);
        try {
            var tasks = new ArrayList<Future<T>>();
            for (int index = 0; index < size; index++) {
                tasks.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return operation.call();
                }));
            }

            var results = new ArrayList<T>();
            for (Future<T> task : tasks) {
                results.add(task.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
