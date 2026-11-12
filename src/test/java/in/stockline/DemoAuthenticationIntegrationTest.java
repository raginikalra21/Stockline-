package in.stockline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import in.stockline.security.DemoAccountResetService;
import in.stockline.security.JdbcUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.Cookie;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=test,local-demo",
    "spring.datasource.url=jdbc:h2:mem:stockline-demo-auth;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000",
    "stockline.demo.initialize=true",
    "stockline.demo.admin.username=demo-admin",
    "stockline.demo.admin.password=StocklineAdmin!2026",
    "stockline.demo.manager.username=demo-manager",
    "stockline.demo.manager.password=StocklineManager!2026",
    "stockline.jwt.secret=VGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS0zMi1ieXRlcy1taW5pbXVt",
    "stockline.fulfillment-delay=1h"
})
@AutoConfigureMockMvc
@Transactional
class DemoAuthenticationIntegrationTest {
    private static final String ADMIN = "demo-admin";
    private static final String ADMIN_PASSWORD = "StocklineAdmin!2026";
    private static final String MANAGER = "demo-manager";
    private static final String MANAGER_PASSWORD = "StocklineManager!2026";

    @Autowired MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired DemoAccountResetService demoAccounts;

    private record Session(MockMvc mvc, Cookie authCookie, Cookie csrfCookie, String csrfToken) {}

    @Test
    void adminAndManagerLoginAndManagerIsRestrictedToPun() throws Exception {
        Session admin = login(ADMIN, ADMIN_PASSWORD);
        admin.mvc().perform(get("/api/auth/me"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        admin.mvc().perform(get("/api/admin/users")).andExpect(status().isOk());
        JsonNode adminCatalog = mapper.readTree(admin.mvc().perform(get("/api/catalog"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(adminCatalog.path("warehouses").size()).isEqualTo(3);
        long networkAvailable = jdbc.queryForObject("SELECT COALESCE(SUM(available), 0) FROM inventory", Long.class);
        long networkProducts = jdbc.queryForObject("SELECT COUNT(DISTINCT sku) FROM inventory", Long.class);
        JsonNode adminOverview = mapper.readTree(admin.mvc().perform(get("/api/overview"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(adminOverview.path("available").asLong()).isEqualTo(networkAvailable);
        assertThat(adminOverview.path("products").asLong()).isEqualTo(networkProducts);

        Session manager = login(MANAGER, MANAGER_PASSWORD);
        manager.mvc().perform(get("/api/auth/me"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("WAREHOUSE_MANAGER"))
            .andExpect(jsonPath("$.warehouseCodes[0]").value("PUN"));
        JsonNode catalog = mapper.readTree(manager.mvc().perform(get("/api/catalog"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(catalog.path("warehouses").size()).isEqualTo(1);
        assertThat(catalog.path("warehouses").get(0).path("code").asText()).isEqualTo("PUN");
        JsonNode inventory = mapper.readTree(manager.mvc().perform(get("/api/inventory"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(inventory.findValuesAsText("warehouseCode")).isNotEmpty().allMatch("PUN"::equals);
        long punAvailable = jdbc.queryForObject("SELECT COALESCE(SUM(available), 0) FROM inventory WHERE warehouse_code = 'PUN'", Long.class);
        long punProducts = jdbc.queryForObject("SELECT COUNT(DISTINCT sku) FROM inventory WHERE warehouse_code = 'PUN'", Long.class);
        JsonNode managerOverview = mapper.readTree(manager.mvc().perform(get("/api/overview"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(managerOverview.path("available").asLong()).isEqualTo(punAvailable);
        assertThat(managerOverview.path("products").asLong()).isEqualTo(punProducts);
        assertThat(managerOverview.path("available").asLong()).isNotEqualTo(networkAvailable);
        JsonNode productInventory = mapper.readTree(manager.mvc().perform(get("/api/inventory/products"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(productInventory.findValuesAsText("warehouseCode")).isNotEmpty().allMatch("PUN"::equals);
        manager.mvc().perform(get("/api/inventory/warehouses/PUN/summary")).andExpect(status().isOk());
        manager.mvc().perform(get("/api/inventory/warehouses/BLR/summary")).andExpect(status().isForbidden());
        manager.mvc().perform(get("/api/inventory/low-stock")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("BLR"))));
        manager.mvc().perform(get("/api/inventory/movements")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("BLR"))));
        manager.mvc().perform(get("/api/inventory/movements").param("warehouseCode", "BLR"))
            .andExpect(status().isForbidden());
        manager.mvc().perform(get("/api/inventory/reconciliation")).andExpect(status().isForbidden());
        manager.mvc().perform(get("/api/admin/users")).andExpect(status().isForbidden());
        manager.mvc().perform(put("/api/allocation/strategy").contentType(MediaType.APPLICATION_JSON)
            .content("{\"strategy\":\"MAXIMUM_AVAILABILITY\"}")).andExpect(status().isForbidden());

        manager.mvc().perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("sku", "KB-104", "warehouseCode", "BLR",
                "quantity", 2, "requestKey", UUID.randomUUID())))).andExpect(status().isForbidden());
        manager.mvc().perform(post("/api/inventory/transfer").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("sku", "KB-104", "fromWarehouse", "PUN", "toWarehouse", "BLR",
                "quantity", 1, "requestKey", UUID.randomUUID())))).andExpect(status().isForbidden());
        manager.mvc().perform(post("/api/inventory/adjustments").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("sku", "KB-104", "warehouseCode", "BLR",
                "stockBucket", "AVAILABLE", "quantityChange", 1, "reason", "Scope test",
                "requestKey", UUID.randomUUID())))).andExpect(status().isForbidden());
        manager.mvc().perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("customer", "Unauthorized warehouse",
                "requestKey", UUID.randomUUID(), "preferredWarehouseCode", "BLR",
                "lines", List.of(Map.of("sku", "KB-104", "quantity", 1)))))).andExpect(status().isForbidden());
        manager.mvc().perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("sku", "KB-104", "warehouseCode", "PUN",
                "quantity", 1, "requestKey", UUID.randomUUID())))).andExpect(status().isOk());

        String[] orderIds = new String[2];
        orderIds[0] = createOrder(admin, "PUN");
        orderIds[1] = createOrder(admin, "BLR");
        JsonNode managerOrders = mapper.readTree(manager.mvc().perform(get("/api/orders"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(managerOrders.findValuesAsText("warehouseCode")).isNotEmpty()
            .allMatch("PUN"::equals);
        manager.mvc().perform(get("/api/orders/{id}", orderIds[0])).andExpect(status().isOk());
        manager.mvc().perform(get("/api/orders/{id}/allocations", orderIds[0])).andExpect(status().isOk());
        manager.mvc().perform(get("/api/orders/{id}", orderIds[1])).andExpect(status().isForbidden());
        manager.mvc().perform(get("/api/orders/{id}/allocations", orderIds[1])).andExpect(status().isForbidden());

        MvcResult logout = manager.mvc().perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(logout.getResponse().getHeaders("Set-Cookie")).anySatisfy(cookie ->
            assertThat(cookie).contains("STOCKLINE_AUTH=").contains("Max-Age=0"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void badPasswordIsRejectedAndLocalDemoAssetContainsTheVisibleDemoCards() throws Exception {
        Session anonymous = loginCsrfOnly();
        mvc.perform(post("/api/auth/login").cookie(anonymous.csrfCookie()).header("X-XSRF-TOKEN", anonymous.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", ADMIN, "password", "incorrect-demo-password"))))
            .andExpect(status().isUnauthorized());

        String asset = mvc.perform(get("/demo-accounts.js")).andExpect(status().isOk())
            .andExpect(content().contentType("application/javascript")).andReturn().getResponse().getContentAsString();
        assertThat(asset.contains(ADMIN) && asset.contains(ADMIN_PASSWORD)
            && asset.contains(MANAGER) && asset.contains(MANAGER_PASSWORD)).isTrue();
        String adminApi = login(ADMIN, ADMIN_PASSWORD).mvc().perform(get("/api/admin/users"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(adminApi.contains(ADMIN_PASSWORD) || adminApi.contains(MANAGER_PASSWORD)).isFalse();
    }

    @Test
    void explicitResetIsIdempotentAndLeavesUnrelatedUsersUntouched() {
        UserAccount unrelated = users.create("unrelated-manager", passwords.encode("unrelated-password-123"),
            Role.WAREHOUSE_MANAGER, List.of("DEL"));
        String unrelatedHash = unrelated.passwordHash();
        UserAccount manager = users.findByUsername(MANAGER).orElseThrow();
        users.update(manager.id(), Role.WAREHOUSE_MANAGER, false, List.of("DEL"));
        users.changePassword(manager.id(), passwords.encode("old-demo-password-123"));

        demoAccounts.initialize(ADMIN, ADMIN_PASSWORD, MANAGER, MANAGER_PASSWORD, "PUN");
        demoAccounts.initialize(ADMIN, ADMIN_PASSWORD, MANAGER, MANAGER_PASSWORD, "PUN");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username = ?", Integer.class, ADMIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username = ?", Integer.class, MANAGER)).isEqualTo(1);
        UserAccount resetManager = users.findByUsername(MANAGER).orElseThrow();
        assertThat(resetManager.enabled()).isTrue();
        assertThat(resetManager.role()).isEqualTo(Role.WAREHOUSE_MANAGER);
        assertThat(resetManager.warehouseCodes()).containsExactly("PUN");
        assertThat(passwords.matches(MANAGER_PASSWORD, resetManager.passwordHash())).isTrue();
        UserAccount unchanged = users.findById(unrelated.id()).orElseThrow();
        assertThat(unchanged.enabled()).isTrue();
        assertThat(unchanged.warehouseCodes()).containsExactly("DEL");
        assertThat(unchanged.passwordHash()).isEqualTo(unrelatedHash);
    }

    private String createOrder(Session session, String warehouse) throws Exception {
        String response = session.mvc().perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("customer", "Scope " + warehouse,
                    "requestKey", UUID.randomUUID(), "preferredWarehouseCode", warehouse,
                    "lines", List.of(Map.of("sku", "KB-104", "quantity", 1))))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("id").asText();
    }

    private Session login(String username, String password) throws Exception {
        Session csrf = loginCsrfOnly();
        MvcResult result = mvc.perform(post("/api/auth/login").cookie(csrf.csrfCookie())
                .header("X-XSRF-TOKEN", csrf.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andReturn();
        Cookie authCookie = result.getResponse().getCookie("STOCKLINE_AUTH");
        MockMvc authenticated = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(get("/").cookie(authCookie, csrf.csrfCookie()).header("X-XSRF-TOKEN", csrf.csrfToken()))
            .build();
        return new Session(authenticated, authCookie, csrf.csrfCookie(), csrf.csrfToken());
    }

    private Session loginCsrfOnly() throws Exception {
        MvcResult response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(response.getResponse().getContentAsString()).path("token").asText();
        return new Session(mvc, null, response.getResponse().getCookie("XSRF-TOKEN"), token);
    }
}
