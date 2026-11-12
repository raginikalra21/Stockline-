package in.stockline.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("local-demo")
public class LocalDemoProfileConfiguration {
    private final Environment environment;

    public LocalDemoProfileConfiguration(Environment environment) {
        this.environment = environment;
        rejectProductionCombination();
    }

    @Bean
    ApplicationRunner initializeLocalDemoAccounts(DemoAccountResetService accounts,
            @Value("${stockline.demo.enabled:false}") boolean enabled,
            @Value("${stockline.demo.initialize:false}") boolean initialize,
            @Value("${stockline.demo.admin.username:}") String adminUsername,
            @Value("${stockline.demo.admin.password:}") String adminPassword,
            @Value("${stockline.demo.manager.username:}") String managerUsername,
            @Value("${stockline.demo.manager.password:}") String managerPassword,
            @Value("${stockline.demo.manager.warehouse:}") String managerWarehouse,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        return args -> {
            if (!initialize) return;
            rejectProductionCombination();
            if (!enabled) throw new IllegalStateException("Local demo accounts are disabled.");
            boolean testDatabase = environment.acceptsProfiles(Profiles.of("test"))
                && datasourceUrl.startsWith("jdbc:h2:mem:");
            boolean localDevelopmentDatabase = datasourceUrl.startsWith("jdbc:h2:file:./data/stockline");
            if (!testDatabase && !localDevelopmentDatabase)
                throw new IllegalStateException("Demo account initialization is limited to the local H2 demo database.");
            accounts.initialize(adminUsername, adminPassword, managerUsername, managerPassword, managerWarehouse);
        };
    }

    private void rejectProductionCombination() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The local-demo profile cannot be active with the production profile.");
        }
    }
}
