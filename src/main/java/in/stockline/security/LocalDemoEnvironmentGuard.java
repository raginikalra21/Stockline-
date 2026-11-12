package in.stockline.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public class LocalDemoEnvironmentGuard implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        boolean nonProductionProfile = environment.acceptsProfiles(Profiles.of("dev | test | benchmark | local-demo"));
        if (production && nonProductionProfile) {
            throw new IllegalStateException("Development and test profiles are forbidden in production.");
        }
        boolean demoRequested = environment.acceptsProfiles(Profiles.of("local-demo"))
            || environment.getProperty("stockline.demo.enabled", Boolean.class, false)
            || environment.getProperty("stockline.demo.initialize", Boolean.class, false);
        if (production && demoRequested) {
            throw new IllegalStateException("Demo credentials and account initialization are forbidden in production.");
        }
    }
}
