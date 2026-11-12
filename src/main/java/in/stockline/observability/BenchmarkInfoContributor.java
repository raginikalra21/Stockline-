package in.stockline.observability;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Identifies the disposable benchmark profile to the benchmark runner. */
@Component
@Profile("benchmark")
public class BenchmarkInfoContributor implements InfoContributor {
    private final JdbcTemplate jdbc;

    public BenchmarkInfoContributor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void contribute(Info.Builder builder) {
        Map<String, Object> stockline = new LinkedHashMap<>();
        stockline.put("benchmarkIsolated", true);
        stockline.put("database", jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
            connection -> connection.getMetaData().getDatabaseProductName()));
        stockline.put("applicationVersion", java.util.Optional.ofNullable(
            BenchmarkInfoContributor.class.getPackage().getImplementationVersion()).orElse("development"));
        builder.withDetail("stockline", stockline);
    }
}
