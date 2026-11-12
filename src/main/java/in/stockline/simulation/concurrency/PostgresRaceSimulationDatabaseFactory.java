package in.stockline.simulation.concurrency;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.stockline.simulation.concurrency.SimulationModels.RaceInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.UUID;

/** Creates disposable schemas so production simulations exercise PostgreSQL without touching live tables. */
@Component
@Profile("prod")
public class PostgresRaceSimulationDatabaseFactory implements RaceSimulationDatabaseFactory {
    private final DataSource adminDataSource;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresRaceSimulationDatabaseFactory(DataSource adminDataSource,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        this.adminDataSource = adminDataSource;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public RaceSimulationDatabase create(RaceInput input) {
        String schema = "stockline_race_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(adminDataSource);
        admin.execute("CREATE SCHEMA " + schema);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(withSchema(jdbcUrl, schema));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.min(32, Math.max(4, input.workers() + 2)));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        HikariDataSource isolated = new HikariDataSource(config);
        AutoCloseable cleanup = () -> {
            isolated.close();
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        };
        try {
            return new RaceSimulationDatabase(input, isolated, true, cleanup);
        } catch (RuntimeException failure) {
            try {
                cleanup.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private String withSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }
}
