package in.stockline.security;

import in.stockline.domain.model.Models.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration
public class SecurityBeans {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean @Profile("!local-demo") ApplicationRunner bootstrapAdmin(JdbcUserRepository users, PasswordEncoder encoder,
            @Value("${stockline.admin.username:}") String username,
            @Value("${stockline.admin.password:}") String password) {
        return args -> {
            if (username.isBlank() && password.isBlank()) return;
            if (username.isBlank() || password.length() < 12)
                throw new IllegalStateException("Configure both STOCKLINE_ADMIN_USERNAME and a STOCKLINE_ADMIN_PASSWORD of at least 12 characters.");
            if (users.findByUsername(username).isEmpty()) users.create(username, encoder.encode(password), Role.ADMIN, List.of());
        };
    }
}
