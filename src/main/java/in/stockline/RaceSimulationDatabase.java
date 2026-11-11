package in.stockline;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/** Owns one disposable H2 database for one measured strategy run. */
final class RaceSimulationDatabase implements AutoCloseable {
    final DriverManagerDataSource dataSource;
    final JdbcTemplate jdbc;
    final Store store;
    final JdbcTransactionManager transactionManager;
    final InventoryService inventory;
    private final Connection keepAlive;

    RaceSimulationDatabase(Models.RaceInput input) {
        dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:race-" + UUID.randomUUID() + ";LOCK_TIMEOUT=4000", "sa", "");
        try {
            keepAlive = dataSource.getConnection();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not create the isolated simulation database.", failure);
        }
        jdbc = new JdbcTemplate(dataSource);
        store = new Store(jdbc);
        transactionManager = new JdbcTransactionManager(dataSource);
        jdbc.update("INSERT INTO products VALUES ('RACE-01', 'Race Demo Item', 'Demo')");
        for (int warehouse = 0; warehouse < input.warehouses(); warehouse++) {
            String code = "W%02d".formatted(warehouse + 1);
            long units = input.stock() / input.warehouses()
                + (warehouse < input.stock() % input.warehouses() ? 1 : 0);
            jdbc.update("INSERT INTO warehouses VALUES (?, ?, ?, ?)", code, "Demo " + code,
                "Simulation", warehouse + 1);
            jdbc.update("""
                INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
                VALUES ('RACE-01', ?, ?, 0, 0, 0)
                """, code, units);
            jdbc.update("INSERT INTO stock_thresholds VALUES ('RACE-01', ?, 5)", code);
        }
        new InventoryLedgerBootstrap(store, transactionManager).initialize();
        inventory = new InventoryService(store, transactionManager, Duration.ZERO);
    }

    @Override
    public void close() {
        try {
            keepAlive.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not close the isolated simulation database.", failure);
        }
    }
}
