package in.stockline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.url=jdbc:h2:mem:stockline-phase2-api;DB_CLOSE_DELAY=-1",
    "stockline.fulfillment-delay=1h"
})
@AutoConfigureMockMvc
class InventoryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void dashboardMutationAndAuditEndpointsUsePersistedLedgerData() throws Exception {
        UUID restockKey = UUID.randomUUID();
        String restock = mapper.writeValueAsString(Map.of("sku", "KB-104", "warehouseCode", "PUN",
            "quantity", 2, "requestKey", restockKey));
        mvc.perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON).content(restock))
            .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(10));
        mvc.perform(post("/api/inventory/adjustments").contentType(MediaType.APPLICATION_JSON).content(
                mapper.writeValueAsString(Map.of("sku", "KB-104", "warehouseCode", "PUN",
                    "stockBucket", "SHIPPED", "quantityChange", 1, "reason", "Invalid direct shipment edit",
                    "requestKey", UUID.randomUUID()))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/inventory/restock").contentType(MediaType.APPLICATION_JSON).content(restock))
            .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(10));

        mvc.perform(post("/api/inventory/damaged").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
            Map.of("sku", "KB-104", "warehouseCode", "PUN", "quantity", 1, "reason", "Damaged carton", "requestKey", UUID.randomUUID()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.damaged").value(1));
        mvc.perform(post("/api/inventory/threshold").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
            Map.of("sku", "KB-104", "warehouseCode", "PUN", "minimumAvailable", 20))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.minimumAvailable").value(20));
        mvc.perform(get("/api/inventory/alerts")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.sku == 'KB-104' && @.warehouseCode == 'PUN')]").isNotEmpty());

        MvcResult history = mvc.perform(get("/api/inventory/movements")
                .param("search", "keyboard").param("warehouseCode", "PUN").param("type", "RESTOCK"))
            .andExpect(status().isOk()).andReturn();
        List<Models.InventoryMovement> movements = mapper.readValue(history.getResponse().getContentAsString(),
            new TypeReference<>() {});
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).quantityChange()).isEqualTo(2);
        assertThat(movements.get(0).previousStock()).isEqualTo(8);
        assertThat(movements.get(0).newStock()).isEqualTo(10);

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

        jdbc.update("DELETE FROM stock_thresholds WHERE sku = 'KB-104' AND warehouse_code = 'BLR'");
        mvc.perform(get("/api/inventory/alerts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.sku == 'KB-104' && @.warehouseCode == 'BLR')]").isNotEmpty());
        Models.Overview dashboard = mapper.readValue(mvc.perform(get("/api/overview"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Models.Overview.class);
        assertThat(dashboard.available()).isEqualTo(jdbc.queryForObject("SELECT SUM(available) FROM inventory", Long.class));
        assertThat(dashboard.reserved()).isEqualTo(jdbc.queryForObject("SELECT SUM(reserved) FROM inventory", Long.class));
        assertThat(dashboard.stockRows()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Integer.class));

        List<Models.ProductInventory> network = mapper.readValue(mvc.perform(get("/api/inventory/products"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), new TypeReference<>() {});
        Models.ProductInventory keyboard = network.stream().filter(item -> item.sku().equals("KB-104"))
            .findFirst().orElseThrow();
        assertThat(keyboard.available()).isEqualTo(jdbc.queryForObject(
            "SELECT SUM(available) FROM inventory WHERE sku = 'KB-104'", Long.class));
        assertThat(keyboard.reserved()).isEqualTo(jdbc.queryForObject(
            "SELECT SUM(reserved) FROM inventory WHERE sku = 'KB-104'", Long.class));
        assertThat(keyboard.damaged()).isEqualTo(jdbc.queryForObject(
            "SELECT SUM(damaged) FROM inventory WHERE sku = 'KB-104'", Long.class));
        assertThat(keyboard.warehouses()).hasSize(3);
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
        Models.RaceResult benchmark = mapper.readValue(response.getResponse().getContentAsString(), Models.RaceResult.class);
        Models.RaceModeResult naive = benchmark.rounds().get(0).strategies().get(0);
        Models.RaceModeResult optimistic = benchmark.rounds().get(0).strategies().get(1);
        Models.RaceModeResult pessimistic = benchmark.rounds().get(0).strategies().get(2);
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
