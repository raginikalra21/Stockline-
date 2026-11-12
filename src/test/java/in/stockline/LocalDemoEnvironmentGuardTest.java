package in.stockline;

import in.stockline.security.LocalDemoEnvironmentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDemoEnvironmentGuardTest {
    @Test
    void productionRejectsLocalDemoProfile() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod", "local-demo");
        assertThatThrownBy(() -> new LocalDemoEnvironmentGuard().postProcessEnvironment(environment,
            new SpringApplication(StocklineApplication.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forbidden in production");
    }

    @Test
    void productionRejectsDemoInitializationFlag() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource("demo-test", Map.of("stockline.demo.initialize", "true")));
        assertThatThrownBy(() -> new LocalDemoEnvironmentGuard().postProcessEnvironment(environment,
            new SpringApplication(StocklineApplication.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forbidden in production");
    }

    @Test
    void productionRejectsDevelopmentAndTestProfiles() {
        for (String profile : new String[] {"dev", "test", "benchmark"}) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            environment.setActiveProfiles("prod", profile);
            assertThatThrownBy(() -> new LocalDemoEnvironmentGuard().postProcessEnvironment(environment,
                new org.springframework.boot.SpringApplication(in.stockline.StocklineApplication.class)))
                .hasMessageContaining("forbidden in production");
        }
    }
}
