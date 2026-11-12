package in.stockline.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("local-demo")
public class DemoAccountsAssetController {
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final List<Map<String, String>> accounts;

    public DemoAccountsAssetController(ObjectMapper mapper,
            @Value("${stockline.demo.enabled:false}") boolean enabled,
            @Value("${stockline.demo.admin.username:}") String adminUsername,
            @Value("${stockline.demo.admin.password:}") String adminPassword,
            @Value("${stockline.demo.manager.username:}") String managerUsername,
            @Value("${stockline.demo.manager.password:}") String managerPassword) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.accounts = List.of(
            Map.of("id", "admin", "label", "Admin", "username", adminUsername, "password", adminPassword),
            Map.of("id", "manager", "label", "Warehouse Manager", "username", managerUsername, "password", managerPassword));
    }

    @GetMapping(value = "/demo-accounts.js", produces = "application/javascript")
    public ResponseEntity<String> demoAccounts() throws JsonProcessingException {
        String value = enabled ? mapper.writeValueAsString(accounts) : "[]";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType("application/javascript"))
            .body("window.STOCKLINE_DEMO_ACCOUNTS = " + value + ";");
    }
}
