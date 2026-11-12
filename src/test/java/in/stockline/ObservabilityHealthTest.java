package in.stockline;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityHealthTest {
    @Test
    void closedDatabasePoolReportsDownAndExhaustedPoolTimesOut() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:stockline-health;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(300);
        HikariDataSource dataSource = new HikariDataSource(config);
        DataSourceHealthIndicator health = new DataSourceHealthIndicator(dataSource);

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        try (Connection held = dataSource.getConnection()) {
            assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
        }
        dataSource.close();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
