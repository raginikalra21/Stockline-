package in.stockline.security;

import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserRepository {
    private record UserRow(UUID id, String username, String passwordHash, Role role, boolean enabled,
                           java.time.OffsetDateTime createdAt) {}
    private final JdbcTemplate jdbc;
    public JdbcUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT * FROM app_users WHERE username = ?", (rs, row) -> new UserRow(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)), username).stream().findFirst().map(user -> {
                List<String> warehouses = jdbc.query("SELECT warehouse_code FROM user_warehouse_access WHERE user_id = ? ORDER BY warehouse_code",
                    (access, row) -> access.getString(1), user.id());
                return new UserAccount(user.id(), user.username(), user.passwordHash(), user.role(), user.enabled(), user.createdAt(), warehouses);
            });
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.query("SELECT username FROM app_users WHERE id = ?", (rs, row) -> rs.getString(1), id)
            .stream().findFirst().flatMap(this::findByUsername);
    }

    public List<UserAccount> list() {
        return jdbc.query("SELECT username FROM app_users ORDER BY username", (rs, row) -> rs.getString(1))
            .stream().map(this::findByUsername).flatMap(Optional::stream).toList();
    }

    @Transactional
    public UserAccount create(String username, String passwordHash, Role role, List<String> warehouses) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users(id, username, password_hash, role, enabled, created_at) VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)",
            id, username, passwordHash, role.name());
        for (String warehouse : warehouses) jdbc.update("INSERT INTO user_warehouse_access(user_id, warehouse_code) VALUES (?, ?)", id, warehouse);
        return findById(id).orElseThrow();
    }

    @Transactional
    public UserAccount update(UUID id, Role role, boolean enabled, List<String> warehouses) {
        jdbc.update("UPDATE app_users SET role = ?, enabled = ? WHERE id = ?", role.name(), enabled, id);
        jdbc.update("DELETE FROM user_warehouse_access WHERE user_id = ?", id);
        for (String warehouse : warehouses) jdbc.update("INSERT INTO user_warehouse_access(user_id, warehouse_code) VALUES (?, ?)", id, warehouse);
        return findById(id).orElseThrow();
    }

    public boolean warehouseAllowed(UUID userId, String warehouse) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_warehouse_access WHERE user_id = ? AND warehouse_code = ?)", Boolean.class, userId, warehouse));
    }

    public int changePassword(UUID userId, String passwordHash) {
        return jdbc.update("UPDATE app_users SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }
}
