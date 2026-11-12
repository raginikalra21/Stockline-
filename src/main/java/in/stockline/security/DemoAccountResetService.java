package in.stockline.security;

import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("local-demo")
public class DemoAccountResetService {
    private final JdbcUserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public DemoAccountResetService(JdbcUserRepository users, JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.users = users;
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Transactional
    public void initialize(String adminUsername, String adminPassword,
                           String managerUsername, String managerPassword, String managerWarehouse) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM warehouses WHERE code = ?)", Boolean.class, managerWarehouse))) {
            throw new IllegalStateException("The configured demo manager warehouse does not exist.");
        }
        initializeAccount(adminUsername, adminPassword, Role.ADMIN, List.of());
        initializeAccount(managerUsername, managerPassword, Role.WAREHOUSE_MANAGER, List.of(managerWarehouse));
    }

    private void initializeAccount(String username, String password, Role role, List<String> warehouses) {
        String encodedPassword = passwords.encode(password);
        UserAccount existing = users.findByUsername(username).orElse(null);
        if (existing == null) {
            users.create(username, encodedPassword, role, warehouses);
            return;
        }
        users.update(existing.id(), role, true, warehouses);
        users.changePassword(existing.id(), encodedPassword);
    }
}
