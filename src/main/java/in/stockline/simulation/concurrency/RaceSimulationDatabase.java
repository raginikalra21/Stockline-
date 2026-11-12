package in.stockline.simulation.concurrency;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import in.stockline.service.inventory.InventoryLedgerBootstrap;
import in.stockline.simulation.concurrency.SimulationModels.RaceInput;

/** Owns one disposable H2 database for one measured strategy run. */
public final class RaceSimulationDatabase implements AutoCloseable {
    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final Store store;
    public final PlatformTransactionManager transactionManager;
    public final InventoryService inventory;
    private final AutoCloseable closeAction;

    public RaceSimulationDatabase(RaceInput input) {
        this(input, embeddedSource());
    }

    private RaceSimulationDatabase(RaceInput input, SimulationSource source) {
        this.dataSource = source.dataSource();
        this.closeAction = source.keepAlive()::close;
        this.jdbc = new JdbcTemplate(dataSource);
        this.store = new Store(jdbc);
        this.transactionManager = new JdbcTransactionManager(dataSource);
        this.inventory = new InventoryService(store, transactionManager, Duration.ZERO);
        initialize(input);
    }

    public RaceSimulationDatabase(RaceInput input, DataSource dataSource, boolean migrate,
                           AutoCloseable closeAction) {
        this.dataSource = dataSource;
        this.closeAction = closeAction;
        if (migrate) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.store = new Store(jdbc);
        this.transactionManager = new JdbcTransactionManager(dataSource);
        this.inventory = new InventoryService(store, transactionManager, Duration.ZERO);
        initialize(input);
    }

    private static SimulationSource embeddedSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:race-" + UUID.randomUUID() + ";LOCK_TIMEOUT=4000", "sa", "");
        try {
            Connection keepAlive = dataSource.getConnection();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            return new SimulationSource(dataSource, keepAlive);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not create the isolated simulation database.", failure);
        }
    }

    private record SimulationSource(DataSource dataSource, Connection keepAlive) {}

    private void initialize(RaceInput input) {
        jdbc.update("INSERT INTO products VALUES ('RACE-01', 'Race Demo Item', 'Demo')");
        for (int warehouse = 0; warehouse < input.warehouses(); warehouse++) {
            String code = "W%02d".formatted(warehouse + 1);
            long units = input.stock() / input.warehouses()
                + (warehouse < input.stock() % input.warehouses() ? 1 : 0);
            jdbc.update("INSERT INTO warehouses(code, name, city, priority) VALUES (?, ?, ?, ?)", code, "Demo " + code,
                "Simulation", warehouse + 1);
            jdbc.update("""
                INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version)
                VALUES ('RACE-01', ?, ?, 0, 0, 0)
                """, code, units);
            jdbc.update("INSERT INTO stock_thresholds VALUES ('RACE-01', ?, 5)", code);
        }
        new InventoryLedgerBootstrap(store, transactionManager).initialize();
    }

    @Override
    public void close() {
        try {
            closeAction.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not close the isolated simulation database.", failure);
        }
    }
}
