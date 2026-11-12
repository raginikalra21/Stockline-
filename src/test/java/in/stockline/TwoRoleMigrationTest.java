package in.stockline;

import org.h2.jdbcx.JdbcDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoRoleMigrationTest {
    @Test
    void legacyOperatorsBecomeDisabledManagersAndTheSchemaAllowsOnlyTwoRoles() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stockline-role-migration;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("8")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID legacyUser = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users(id, username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)",
            legacyUser, "legacy-operator", "already-hashed", "INVENTORY_OPERATOR");
        jdbc.update("INSERT INTO user_warehouse_access(user_id, warehouse_code) VALUES (?, 'PUN')", legacyUser);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT role FROM app_users WHERE id = ?", String.class, legacyUser))
            .isEqualTo("WAREHOUSE_MANAGER");
        assertThat(jdbc.queryForObject("SELECT enabled FROM app_users WHERE id = ?", Boolean.class, legacyUser)).isFalse();
        assertThat(jdbc.queryForObject("SELECT warehouse_code FROM user_warehouse_access WHERE user_id = ?", String.class, legacyUser))
            .isEqualTo("PUN");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO app_users(id, username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)",
            UUID.randomUUID(), "new-legacy-role", "already-hashed", "INVENTORY_OPERATOR"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
