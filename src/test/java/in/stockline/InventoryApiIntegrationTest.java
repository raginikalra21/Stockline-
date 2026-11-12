package in.stockline;

import in.stockline.domain.model.Models;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import in.stockline.service.inventory.InventoryLedgerBootstrap;
import in.stockline.service.inventory.InventoryService;
import in.stockline.service.audit.InventoryAuditService;
import in.stockline.events.consumer.FulfillmentWorker;
import in.stockline.simulation.concurrency.RaceDemo;
import in.stockline.simulation.concurrency.RaceCounters;
import in.stockline.simulation.concurrency.RaceSimulationDatabase;
import in.stockline.simulation.concurrency.RaceStrategy;
import in.stockline.simulation.concurrency.NaiveDatabaseRaceStrategy;
import in.stockline.simulation.concurrency.OptimisticDatabaseRaceStrategy;
import in.stockline.simulation.concurrency.PessimisticDatabaseRaceStrategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static in.stockline.simulation.concurrency.SimulationModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=test",
    "spring.datasource.url=jdbc:h2:mem:stockline-phase2-api;DB_CLOSE_DELAY=-1",
    "stockline.fulfillment-delay=1h"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InventoryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;

    @BeforeEach
    void authenticateAdminForApiRequests() throws Exception {
        var csrfResponse = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String token = mapper.readTree(csrfResponse.getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResponse.getCookie("XSRF-TOKEN");
        var login = mvc.perform(post("/api/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "test-admin", "password", "test-admin-password-123"))))
            .andExpect(status().isOk()).andReturn().getResponse();
        Cookie authCookie = login.getCookie("STOCKLINE_AUTH");
        MockHttpServletRequestBuilder defaults = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
            .cookie(authCookie, csrfCookie).header("X-XSRF-TOKEN", token);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(defaults).build();
    }

    @Test
    void actuatorHealthIsPublicButDetailedMetricsAreAdminOnly() throws Exception {
        var bareMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        bareMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        bareMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        bareMvc.perform(get("/actuator/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mvc.perform(get("/api/events").param("limit", "101")).andExpect(status().isBadRequest());

        String createUser = mapper.writeValueAsString(Map.of("username", "ops-reader", "password", "ops-reader-password-123",
            "role", "WAREHOUSE_MANAGER", "warehouseCodes", List.of("PUN")));
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(createUser))
            .andExpect(status().isCreated());
        var csrfResponse = bareMvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        String csrf = mapper.readTree(csrfResponse.getContentAsString()).get("token").asText();
        var login = bareMvc.perform(post("/api/auth/login").cookie(csrfResponse.getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", csrf).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "ops-reader", "password", "ops-reader-password-123"))))
            .andReturn().getResponse();
        MockMvc manager = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .cookie(login.getCookie("STOCKLINE_AUTH"), csrfResponse.getCookie("XSRF-TOKEN")))
            .build();
        manager.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
    }

    @Test
    void demoCredentialsAssetIsEmptyOutsideLocalDemoProfile() throws Exception {
        mvc.perform(get("/demo-accounts.js")).andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.startsWith("window.STOCKLINE_DEMO_ACCOUNTS = [];")));
    }

    @Test
    void requestCorrelationIsValidatedReturnedAndPersistedSeparatelyFromOperationCorrelation() throws Exception {
        UUID supplied = UUID.randomUUID();
        Models.Order order = mapper.readValue(mvc.perform(post("/api/orders")
                .header("X-Correlation-ID", supplied).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("customer", "Correlation test", "requestKey", UUID.randomUUID(),
                    "lines", List.of(Map.of("sku", "KB-104", "quantity", 1))))))
            .andExpect(status().isCreated()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("X-Correlation-ID", supplied.toString()))
            .andReturn().getResponse().getContentAsString(), Models.Order.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND request_correlation_id = ?",
            Integer.class, order.id().toString(), supplied)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND correlation_id = ?",
            Integer.class, order.id().toString(), supplied)).isZero();
        mvc.perform(post("/api/orders/" + order.id() + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/stockline.orders.created")).andExpect(status().isOk())
            .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

        var generated = mvc.perform(get("/api/auth/csrf").header("X-Correlation-ID", "unsafe value"))
            .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Correlation-ID");
        assertThat(UUID.fromString(generated)).isNotEqualTo(supplied);
    }

    @Test
    void authenticationFailuresDoNotExposeOrLogCredentials(CapturedOutput output) throws Exception {
        String secret = "sensitive-password-do-not-log";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "missing-user", "password", secret))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Authentication failed."))
            .andExpect(jsonPath("$.correlationId").isNotEmpty());
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void dashboardMutationAndAuditEndpointsUsePersistedLedgerData() throws Exception {
        String sku = "API-LEDGER-TEST";
        String product = mapper.writeValueAsString(Map.of("sku", sku, "name", "API ledger test product", "category", "Test"));
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(product))
            .andExpect(status().isCreated());
        UUID restockKey = UUID.randomUUID();
        String restock = mapper.writeValueAsString(Map.of("sku", sku, "warehouseCode", "PUN",
            "quantity", 2, "requestKey", restockKey));
        mvc.perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON).content(restock))
            .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(2));
        mvc.perform(post("/api/inventory/adjustments").contentType(MediaType.APPLICATION_JSON).content(
                mapper.writeValueAsString(Map.of("sku", sku, "warehouseCode", "PUN",
                    "stockBucket", "SHIPPED", "quantityChange", 1, "reason", "Invalid direct shipment edit",
                    "requestKey", UUID.randomUUID()))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON).content(restock))
            .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(2));

        mvc.perform(post("/api/inventory/damaged").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
            Map.of("sku", sku, "warehouseCode", "PUN", "quantity", 1, "reason", "Damaged carton", "requestKey", UUID.randomUUID()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.damaged").value(1));
        mvc.perform(post("/api/inventory/threshold").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
            Map.of("sku", sku, "warehouseCode", "PUN", "minimumAvailable", 20))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.minimumAvailable").value(20));
        mvc.perform(get("/api/inventory/alerts")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.sku == 'API-LEDGER-TEST' && @.warehouseCode == 'PUN')]").isNotEmpty());

        MvcResult history = mvc.perform(get("/api/inventory/movements")
                .param("search", "API ledger test product").param("warehouseCode", "PUN").param("type", "RESTOCK"))
            .andExpect(status().isOk()).andReturn();
        List<Models.InventoryMovement> movements = mapper.readValue(history.getResponse().getContentAsString(),
            new TypeReference<>() {});
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).quantityChange()).isEqualTo(2);
        assertThat(movements.get(0).previousStock()).isZero();
        assertThat(movements.get(0).newStock()).isEqualTo(2);

        MvcResult reconciliation = mvc.perform(get("/api/inventory/reconciliation"))
            .andExpect(status().isOk()).andReturn();
        List<Models.ReconciliationLine> lines = mapper.readValue(reconciliation.getResponse().getContentAsString(),
            new TypeReference<>() {});
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.difference()).isZero();
            assertThat(line.status()).isEqualTo("MATCH");
            assertThat(line.ledgerChainStatus()).isEqualTo("VALID");
            assertThat(line.overallIntegrityStatus()).isEqualTo("VALID");
            assertThat(line.invalidMovementCount()).isZero();
            assertThat(line.discontinuityCount()).isZero();
            assertThat(line.missingOperationCount()).isZero();
            assertThat(line.unbalancedTransferCount()).isZero();
            assertThat(line.bucketBalanceMismatch()).isFalse();
        });
        mvc.perform(get("/api/overview")).andExpect(status().isOk())
            .andExpect(jsonPath("$.damaged").value(1)).andExpect(jsonPath("$.lowStockProducts").isNumber());

        jdbc.update("DELETE FROM stock_thresholds WHERE sku = 'API-LEDGER-TEST' AND warehouse_code = 'BLR'");
        mvc.perform(get("/api/inventory/alerts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.sku == 'API-LEDGER-TEST' && @.warehouseCode == 'BLR')]").isNotEmpty());
        Models.Overview dashboard = mapper.readValue(mvc.perform(get("/api/overview"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Models.Overview.class);
        assertThat(dashboard.available()).isEqualTo(jdbc.queryForObject("SELECT SUM(available) FROM inventory", Long.class));
        assertThat(dashboard.reserved()).isEqualTo(jdbc.queryForObject("SELECT SUM(reserved) FROM inventory", Long.class));
        assertThat(dashboard.stockRows()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Integer.class));

        List<Models.ProductInventory> network = mapper.readValue(mvc.perform(get("/api/inventory/products"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        Models.ProductInventory keyboard = network.stream().filter(item -> item.sku().equals("API-LEDGER-TEST"))
            .findFirst().orElseThrow();
        assertThat(keyboard.available()).isEqualTo(jdbc.queryForObject(
            "SELECT SUM(available) FROM inventory WHERE sku = 'API-LEDGER-TEST'", Long.class));
        assertThat(keyboard.reserved()).isEqualTo(jdbc.queryForObject(
            "SELECT SUM(reserved) FROM inventory WHERE sku = 'API-LEDGER-TEST'", Long.class));
        assertThat(keyboard.damaged()).isEqualTo(jdbc.queryForObject(
            "SELECT SUM(damaged) FROM inventory WHERE sku = 'API-LEDGER-TEST'", Long.class));
        assertThat(keyboard.warehouses()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE actor_user_id IS NOT NULL AND actor_role = 'ADMIN'", Integer.class))
            .isPositive();
    }

    @Test
    void authenticationAndWarehouseRolesAreEnforcedAndActorsAreRecorded() throws Exception {
        var bareMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        bareMvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());

        String createUser = mapper.writeValueAsString(Map.of("username", "pun-manager", "password", "pun-manager-password-123",
            "role", "WAREHOUSE_MANAGER", "warehouseCodes", List.of("PUN")));
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(createUser))
            .andExpect(status().isCreated());

        var csrfResponse = bareMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String csrf = mapper.readTree(csrfResponse.getContentAsString()).get("token").asText();
        Cookie csrfCookie = csrfResponse.getCookie("XSRF-TOKEN");
        var login = bareMvc.perform(post("/api/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "pun-manager", "password", "pun-manager-password-123"))))
            .andExpect(status().isOk()).andReturn().getResponse();
        MockHttpServletRequestBuilder defaults = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
            .cookie(login.getCookie("STOCKLINE_AUTH"), csrfCookie).header("X-XSRF-TOKEN", csrf);
        MockMvc managerMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(defaults).build();
        managerMvc.perform(get("/api/inventory")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].warehouseCode").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PUN"))));
        managerMvc.perform(get("/api/inventory/products")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].warehouses[*].warehouseCode")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PUN"))));
        managerMvc.perform(get("/api/inventory/network-summary")).andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(jdbc.queryForObject(
                "SELECT SUM(available) FROM inventory WHERE warehouse_code = 'PUN'", Long.class)));
        managerMvc.perform(get("/api/inventory/warehouses/DEL/summary")).andExpect(status().isForbidden());
        managerMvc.perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("sku", "KB-104", "warehouseCode", "DEL", "quantity", 1,
                "requestKey", UUID.randomUUID()))))
            .andExpect(status().isForbidden());
        var orderResponse = managerMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("customer", "Scoped manager", "requestKey", UUID.randomUUID(),
                "preferredWarehouseCode", "PUN", "lines", List.of(Map.of("sku", "KB-104", "quantity", 1))))))
            .andExpect(status().isCreated()).andReturn().getResponse();
        UUID orderId = mapper.readValue(orderResponse.getContentAsString(), Models.Order.class).id();
        managerMvc.perform(post("/api/orders/" + orderId + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
        UUID managerId = jdbc.queryForObject("SELECT id FROM app_users WHERE username = 'pun-manager'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE actor_user_id = ? AND actor_role = 'WAREHOUSE_MANAGER'",
            Integer.class, managerId)).isEqualTo(4);
    }

    @Test
    void warehouseOrderPaginationAppliesScopeBeforeLimitAndOffset() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
            Map.of("sku", "PAGE-01", "name", "Pagination product", "category", "Test"))))
            .andExpect(status().isCreated());
        for (String warehouse : List.of("PUN", "DEL")) {
            mvc.perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("sku", "PAGE-01", "warehouseCode", warehouse, "quantity", 10, "requestKey", UUID.randomUUID()))))
                .andExpect(status().isOk());
        }

        List<UUID> orderIds = new java.util.ArrayList<>();
        java.time.OffsetDateTime pageOrderBase = java.time.OffsetDateTime.now().plusSeconds(1);
        int orderIndex = 0;
        for (String warehouse : List.of("PUN", "PUN", "DEL", "DEL", "DEL")) {
            Models.Order order = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("customer", "Page scope " + warehouse,
                        "requestKey", UUID.randomUUID(), "preferredWarehouseCode", warehouse,
                        "lines", List.of(Map.of("sku", "PAGE-01", "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Models.Order.class);
            orderIds.add(order.id());
            jdbc.update("UPDATE customer_orders SET created_at = ? WHERE id = ?",
                pageOrderBase.plusSeconds(orderIndex++), order.id());
        }

        String createUser = mapper.writeValueAsString(Map.of("username", "page-reader", "password", "page-reader-password-123",
            "role", "WAREHOUSE_MANAGER", "warehouseCodes", List.of("PUN")));
        mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(createUser))
            .andExpect(status().isCreated());
        var bareMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var csrfResponse = bareMvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        String csrf = mapper.readTree(csrfResponse.getContentAsString()).get("token").asText();
        var login = bareMvc.perform(post("/api/auth/login").cookie(csrfResponse.getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", csrf).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "page-reader", "password", "page-reader-password-123"))))
            .andExpect(status().isOk()).andReturn().getResponse();
        MockMvc manager = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .cookie(login.getCookie("STOCKLINE_AUTH"), csrfResponse.getCookie("XSRF-TOKEN")))
            .build();

        List<Models.Order> visibleFirstPage = mapper.readValue(manager.perform(get("/api/orders")
                .param("limit", "2").param("offset", "0")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(visibleFirstPage).hasSize(2).allSatisfy(order ->
            assertThat(order.allocations()).allSatisfy(allocation -> assertThat(allocation.warehouseCode()).isEqualTo("PUN")));
        List<Models.Order> visibleSecondPage = mapper.readValue(manager.perform(get("/api/orders")
                .param("limit", "2").param("offset", "2")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(visibleSecondPage).allSatisfy(order ->
            assertThat(order.allocations()).allSatisfy(allocation -> assertThat(allocation.warehouseCode()).isEqualTo("PUN")));

        for (UUID orderId : orderIds) mvc.perform(post("/api/orders/" + orderId + "/cancel")
            .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void eventSearchMatchesEventAndAggregateIdsAndRejectsOversizedQueries() throws Exception {
        Models.Order order = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("customer", "Event search", "requestKey", UUID.randomUUID(),
                    "lines", List.of(Map.of("sku", "KB-104", "quantity", 1))))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Models.Order.class);
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_RESERVED'",
            UUID.class, order.id().toString());

        List<Models.OutboxEvent> matchingOrderEvents = mapper.readValue(mvc.perform(get("/api/events")
                .param("search", order.id().toString()).param("limit", "25"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(matchingOrderEvents).extracting(Models.OutboxEvent::eventId).contains(eventId);
        mvc.perform(get("/api/events").param("search", eventId.toString().substring(0, 12)).param("limit", "25"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].aggregateId").value(order.id().toString()));
        mvc.perform(get("/api/events").param("search", "x".repeat(101)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/orders/" + order.id() + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    void orderListingBatchReturnsMultipleWarehouseAllocations() throws Exception {
        UUID firstKey = UUID.randomUUID();
        String firstOrderJson = mapper.writeValueAsString(Map.of("customer", "Allocation test one",
            "requestKey", firstKey, "lines", List.of(Map.of("sku", "HD-410", "quantity", 12))));
        Models.Order first = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(firstOrderJson)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
            Models.Order.class);

        UUID secondKey = UUID.randomUUID();
        String secondOrderJson = mapper.writeValueAsString(Map.of("customer", "Allocation test two",
            "requestKey", secondKey, "lines", List.of(Map.of("sku", "CB-305", "quantity", 40))));
        Models.Order second = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(secondOrderJson)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
            Models.Order.class);

        List<Models.Order> listed = mapper.readValue(mvc.perform(get("/api/orders").param("limit", "100"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(listed).anySatisfy(order -> {
            assertThat(order.id()).isEqualTo(first.id());
            assertThat(order.allocations()).hasSize(3);
        });
        assertThat(listed).anySatisfy(order -> {
            assertThat(order.id()).isEqualTo(second.id());
            assertThat(order.allocations()).hasSize(2);
        });
    }

    @Test
    @Transactional
    void cb305ReservationCancellationAndFulfillmentUpdatePersistedWarehouseBalances() throws Exception {
        // Set a repeatable fixture through authorized business operations, including cleanup of prior reservations.
        List<Models.Order> existing = mapper.readValue(mvc.perform(get("/api/orders").param("limit", "100"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        for (Models.Order prior : existing) {
            if (prior.status() == Models.OrderStatus.RESERVED && prior.allocations().stream()
                    .anyMatch(allocation -> allocation.sku().equals("CB-305"))) {
                mvc.perform(post("/api/orders/" + prior.id() + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
            }
        }
        for (var baseline : Map.of("PUN", 18L, "DEL", 27L, "BLR", 19L).entrySet()) {
            long current = jdbc.queryForObject("SELECT available FROM inventory WHERE sku='CB-305' AND warehouse_code=?",
                Long.class, baseline.getKey());
            if (current != baseline.getValue()) {
                mvc.perform(post("/api/inventory/adjustments").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("sku", "CB-305", "warehouseCode", baseline.getKey(),
                            "stockBucket", "AVAILABLE", "quantityChange", baseline.getValue() - current,
                            "reason", "CB-305 regression fixture", "requestKey", UUID.randomUUID()))))
                    .andExpect(status().isOk());
            }
        }
        assertThat(jdbc.queryForObject("SELECT available FROM inventory WHERE sku='CB-305' AND warehouse_code='PUN'", Long.class)).isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT available FROM inventory WHERE sku='CB-305' AND warehouse_code='DEL'", Long.class)).isEqualTo(27);
        assertThat(jdbc.queryForObject("SELECT available FROM inventory WHERE sku='CB-305' AND warehouse_code='BLR'", Long.class)).isEqualTo(19);

        var order = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("customer", "CB-305 regression", "requestKey", UUID.randomUUID(),
                    "preferredWarehouseCode", "PUN", "lines", List.of(Map.of("sku", "CB-305", "quantity", 20))))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Models.Order.class);
        assertThat(order.allocations()).allSatisfy(allocation -> assertThat(allocation.sku()).isEqualTo("CB-305"));
        assertThat(order.allocations().stream().mapToInt(Models.Allocation::quantity).sum()).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT SUM(available) FROM inventory WHERE sku='CB-305'", Long.class)).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT SUM(reserved) FROM inventory WHERE sku='CB-305'", Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT SUM(available + reserved + damaged) FROM inventory WHERE sku='CB-305'", Long.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM allocations WHERE order_id=?", Integer.class, order.id())).isEqualTo(20);
        assertThat(mapper.readTree(mvc.perform(get("/api/inventory/products/CB-305/distribution"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("available").asLong()).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE reference_order_id=? AND movement_type='RESERVATION'", Integer.class, order.id())).isEqualTo(order.allocations().size() * 2);
        assertThat(new InventoryAuditService(new Store(jdbc)).reconcile()).allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));

        mvc.perform(post("/api/orders/" + order.id() + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT SUM(available) FROM inventory WHERE sku='CB-305'", Long.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT SUM(reserved) FROM inventory WHERE sku='CB-305'", Long.class)).isZero();

        var fulfillmentOrder = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("customer", "CB-305 fulfillment", "requestKey", UUID.randomUUID(),
                    "lines", List.of(Map.of("sku", "CB-305", "quantity", 20))))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Models.Order.class);
        assertThat(inventory.fulfill(fulfillmentOrder.id())).isTrue();
        assertThat(mapper.readTree(mvc.perform(get("/api/orders/" + fulfillmentOrder.id()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("status").asText()).isEqualTo("FULFILLED");
        assertThat(jdbc.queryForObject("SELECT SUM(available) FROM inventory WHERE sku='CB-305'", Long.class)).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT SUM(reserved) FROM inventory WHERE sku='CB-305'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(shipped) FROM inventory WHERE sku='CB-305'", Long.class)).isEqualTo(20);
        assertThat(new InventoryAuditService(new Store(jdbc)).reconcile()).allMatch(line -> "VALID".equals(line.overallIntegrityStatus()));
    }

    @Test
    void allocationPreviewAndPersistedOrderDecisionsAreExposedWithoutPreviewMutation() throws Exception {
        long availableBefore = jdbc.queryForObject("SELECT SUM(available) FROM inventory WHERE sku = 'KB-104'", Long.class);
        int orderCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class);
        int movementCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class);
        String previewBody = mapper.writeValueAsString(Map.of("lines", List.of(Map.of("sku", "KB-104", "quantity", 2)),
            "allocationStrategy", "SINGLE_WAREHOUSE", "deliveryLatitude", 28.6139, "deliveryLongitude", 77.2090));
        mvc.perform(post("/api/orders/allocation-preview").contentType(MediaType.APPLICATION_JSON).content(previewBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fullyFulfillable").value(true))
            .andExpect(jsonPath("$.strategy").value("SINGLE_WAREHOUSE"))
            .andExpect(jsonPath("$.warehousesInvolved").value(1))
            .andExpect(jsonPath("$.allocations[0].availableBefore").isNumber());
        assertThat(jdbc.queryForObject("SELECT SUM(available) FROM inventory WHERE sku = 'KB-104'", Long.class))
            .isEqualTo(availableBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class)).isEqualTo(orderCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class)).isEqualTo(movementCountBefore);

        String orderBody = mapper.writeValueAsString(Map.of("customer", "Decision detail buyer", "requestKey", UUID.randomUUID(),
            "allocationStrategy", "SINGLE_WAREHOUSE", "lines", List.of(Map.of("sku", "KB-104", "quantity", 2))));
        Models.Order order = mapper.readValue(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(orderBody)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Models.Order.class);
        mvc.perform(get("/api/orders/" + order.id() + "/allocations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].strategy").value("SINGLE_WAREHOUSE"))
            .andExpect(jsonPath("$[0].status").value("RESERVED"))
            .andExpect(jsonPath("$[0].reason").isNotEmpty());
    }

    @Test
    void concurrencyComparisonEndpointRunsThreeStrategiesAndKeepsProductionInventoryUntouched() throws Exception {
        long availableBefore = jdbc.queryForObject("SELECT COALESCE(SUM(available), 0) FROM inventory", Long.class);
        long reservedBefore = jdbc.queryForObject("SELECT COALESCE(SUM(reserved), 0) FROM inventory", Long.class);
        String body = mapper.writeValueAsString(Map.of("buyers", 100, "stock", 10, "quantity", 1,
            "warehouses", 2, "workers", 10, "durationSeconds", 30, "mode", "COMPARE", "repetitions", 1));
        MvcResult response = mvc.perform(post("/api/demo/race").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rounds.length()").value(1))
            .andExpect(jsonPath("$.rounds[0].strategies.length()").value(3))
            .andExpect(jsonPath("$.rounds[0].strategies[0].mode").value("NAIVE"))
            .andExpect(jsonPath("$.rounds[0].strategies[1].mode").value("OPTIMISTIC"))
            .andExpect(jsonPath("$.rounds[0].strategies[2].mode").value("PESSIMISTIC"))
            .andExpect(jsonPath("$.rounds[0].strategies[1].isolationLevel").value("READ_COMMITTED"))
            .andExpect(jsonPath("$.rounds[0].strategies[2].correct").value(true)).andReturn();
        RaceResult benchmark = mapper.readValue(response.getResponse().getContentAsString(), RaceResult.class);
        RaceModeResult naive = benchmark.rounds().get(0).strategies().get(0);
        RaceModeResult optimistic = benchmark.rounds().get(0).strategies().get(1);
        RaceModeResult pessimistic = benchmark.rounds().get(0).strategies().get(2);
        assertThat(naive.accepted()).isEqualTo(100);
        assertThat(naive.oversold()).isEqualTo(90);
        assertThat(naive.lostUpdates()).isEqualTo(90);
        assertThat(naive.correct()).isFalse();
        assertThat(optimistic.accepted()).isEqualTo(10);
        assertThat(optimistic.optimisticConflicts()).isPositive();
        assertThat(optimistic.correct()).isTrue();
        assertThat(pessimistic.accepted()).isEqualTo(10);
        assertThat(pessimistic.databaseTimeMs()).isPositive();
        assertThat(pessimistic.correct()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(available), 0) FROM inventory", Long.class))
            .isEqualTo(availableBefore);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(reserved), 0) FROM inventory", Long.class))
            .isEqualTo(reservedBefore);
    }
}
