package in.stockline.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Function;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.ConnectionCallback;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.domain.model.AllocationModels.AllocationCandidate;
import in.stockline.observability.CorrelationContext;
import in.stockline.observability.StocklineMetrics;
import in.stockline.domain.model.AllocationModels.ProposedAllocation;
import static in.stockline.domain.model.Models.*;

@Repository
public class Store {
    private static final RowMapper<Stock> STOCK = (rs, row) -> new Stock(
        rs.getString("sku"), rs.getString("warehouse_code"), rs.getLong("available"),
        rs.getLong("reserved"), rs.getLong("damaged"), rs.getLong("shipped"),
        rs.getLong("written_off"), rs.getLong("minimum_available"), rs.getLong("version")
    );
    private record ProductWarehouseRow(String sku, String product, String warehouseCode, String warehouse,
                                       long available, long reserved, long damaged, long shipped, long writtenOff,
                                       long minimumAvailable, WarehouseStatus warehouseStatus,
                                       int priority) {}
    private static final class ProductInventoryAccumulator {
        final String sku;
        final String product;
        long totalStock;
        long available;
        long reserved;
        long damaged;
        long shipped;
        long writtenOff;
        int stockingWarehouses;
        final List<String> belowThresholdWarehouses = new java.util.ArrayList<>();
        final List<WarehouseBalance> warehouses = new java.util.ArrayList<>();

        ProductInventoryAccumulator(String sku, String product) {
            this.sku = sku;
            this.product = product;
        }

        void add(ProductWarehouseRow row) {
            long physical = row.available() + row.reserved() + row.damaged();
            totalStock += physical;
            available += row.available();
            reserved += row.reserved();
            damaged += row.damaged();
            shipped += row.shipped();
            writtenOff += row.writtenOff();
            if (physical > 0) stockingWarehouses++;
            boolean below = row.available() < row.minimumAvailable();
            if (below) belowThresholdWarehouses.add(row.warehouseCode());
            warehouses.add(new WarehouseBalance(row.warehouseCode(), row.warehouse(), physical,
                row.available(), row.reserved(), row.damaged(), row.shipped(), row.writtenOff(),
                row.minimumAvailable(), below, row.warehouseStatus(), row.available() == 0
                    ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.AVAILABLE));
        }

        ProductInventory result() {
            return new ProductInventory(sku, product, totalStock, available, reserved, damaged,
                shipped, writtenOff, stockingWarehouses, List.copyOf(belowThresholdWarehouses),
                available == 0 ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.AVAILABLE,
                List.copyOf(warehouses));
        }
    }
    private static final RowMapper<Allocation> ALLOCATION = (rs, row) -> new Allocation(
        rs.getString("sku"), rs.getString("warehouse_code"), rs.getInt("quantity")
    );

    public record OrderHeader(UUID id, String customer, OrderStatus status, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, String fingerprint) {
        public Order withAllocations(List<Allocation> allocations) {
            return new Order(id, customer, status, createdAt, updatedAt, allocations);
        }
    }

    private static final RowMapper<OrderHeader> ORDER = (rs, row) -> new OrderHeader(
        rs.getObject("id", UUID.class), rs.getString("customer"), OrderStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
        rs.getString("fingerprint")
    );
    private static final RowMapper<OutboxEvent> EVENT = (rs, row) -> new OutboxEvent(
        rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("aggregate_type"),
        rs.getString("aggregate_id"), rs.getString("payload"), rs.getObject("created_at", OffsetDateTime.class),
        rs.getString("status"), rs.getInt("attempt_count"), rs.getObject("next_attempt_at", OffsetDateTime.class),
        rs.getString("last_error"), rs.getString("failure_kind"), rs.getObject("processing_at", OffsetDateTime.class),
        rs.getString("claimed_by"), rs.getObject("lease_until", OffsetDateTime.class),
        rs.getObject("processed_at", OffsetDateTime.class), rs.getObject("correlation_id", UUID.class),
        rs.getString("dedupe_key"), rs.getObject("request_correlation_id", UUID.class)
    );
    private static final RowMapper<EventAttempt> ATTEMPT = (rs, row) -> new EventAttempt(
        rs.getObject("attempt_id", UUID.class), rs.getObject("event_id", UUID.class), rs.getInt("attempt_number"),
        rs.getString("worker_id"), rs.getObject("started_at", OffsetDateTime.class),
        rs.getObject("completed_at", OffsetDateTime.class), rs.getString("outcome"), rs.getString("error_message"));
    private static final RowMapper<ReplenishmentSuggestion> SUGGESTION = (rs, row) -> new ReplenishmentSuggestion(
        rs.getObject("suggestion_id", UUID.class), rs.getObject("event_id", UUID.class), rs.getString("sku"),
        rs.getString("product"), rs.getString("warehouse_code"), rs.getString("warehouse"), rs.getLong("available"),
        rs.getLong("minimum_available"), rs.getLong("recommended_quantity"), rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RowMapper<InventoryMovement> MOVEMENT = (rs, row) -> new InventoryMovement(
        rs.getObject("movement_id", UUID.class), rs.getString("product_id"), rs.getString("warehouse_id"),
        MovementType.valueOf(rs.getString("movement_type")), StockBucket.valueOf(rs.getString("stock_bucket")),
        rs.getLong("quantity_change"), rs.getLong("previous_stock"), rs.getLong("new_stock"),
        rs.getObject("reference_order_id", UUID.class), rs.getString("source_warehouse"),
        rs.getString("destination_warehouse"), rs.getString("reason"), rs.getString("actor"),
        rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("operation_id", UUID.class),
        rs.getObject("actor_user_id", UUID.class), rs.getString("actor_role")
    );

    public record InventoryOperation(UUID operationId, UUID requestKey, String operationType,
                              String payloadHash, OffsetDateTime createdAt) {}
    private static final RowMapper<InventoryOperation> OPERATION = (rs, row) -> new InventoryOperation(
        rs.getObject("operation_id", UUID.class), rs.getObject("request_key", UUID.class),
        rs.getString("operation_type"), rs.getString("payload_hash"),
        rs.getObject("created_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbc;
    private final TransactionTemplate eventTransaction;
    private final StocklineMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public Store(JdbcTemplate jdbc, StocklineMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.eventTransaction = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        this.eventTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.metrics = new StocklineMetrics();
        this.eventTransaction = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        this.eventTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Catalog catalog() {
        var products = jdbc.query("SELECT * FROM products ORDER BY name, sku",
            (rs, row) -> new Product(rs.getString("sku"), rs.getString("name"), rs.getString("category")));
        var warehouses = jdbc.query("SELECT * FROM warehouses ORDER BY priority, code", (rs, row) -> warehouse(rs));
        return new Catalog(products, warehouses);
    }

    private Warehouse warehouse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Warehouse(rs.getString("code"), rs.getString("name"), rs.getString("city"),
            rs.getInt("priority"), WarehouseStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("latitude") == null ? null : rs.getBigDecimal("latitude").doubleValue(),
            rs.getBigDecimal("longitude") == null ? null : rs.getBigDecimal("longitude").doubleValue(),
            rs.getBigDecimal("handling_cost"), rs.getBigDecimal("shipping_cost_per_unit"),
            (Integer) rs.getObject("max_fulfillment_capacity"));
    }

    public void addProduct(Product product) {
        lockCatalog();
        jdbc.update("INSERT INTO products VALUES (?, ?, ?)", product.sku(), product.name(), product.category());
        jdbc.update("INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version) "
            + "SELECT ?, code, 0, 0, 0, 0 FROM warehouses", product.sku());
        jdbc.update("INSERT INTO stock_thresholds SELECT ?, code, 5 FROM warehouses", product.sku());
        jdbc.update("INSERT INTO low_stock_event_state(sku, warehouse_code, is_low, updated_at) "
            + "SELECT ?, code, FALSE, CURRENT_TIMESTAMP FROM warehouses", product.sku());
    }

    public void addWarehouse(Warehouse warehouse) {
        lockCatalog();
        jdbc.update("INSERT INTO warehouses(code, name, city, priority) VALUES (?, ?, ?, ?)",
            warehouse.code(), warehouse.name(), warehouse.city(), warehouse.priority());
        jdbc.update("INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version) "
            + "SELECT sku, ?, 0, 0, 0, 0 FROM products", warehouse.code());
        jdbc.update("INSERT INTO stock_thresholds SELECT sku, ?, 5 FROM products", warehouse.code());
        jdbc.update("INSERT INTO low_stock_event_state(sku, warehouse_code, is_low, updated_at) "
            + "SELECT sku, ?, FALSE, CURRENT_TIMESTAMP FROM products", warehouse.code());
    }

    private void lockCatalog() {
        jdbc.queryForObject("SELECT id FROM catalog_guard WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public List<Stock> inventory() {
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available
            FROM inventory i JOIN warehouses w ON i.warehouse_code = w.code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            ORDER BY i.sku, w.priority, w.code
            """, STOCK);
    }

    public NetworkInventorySummary networkInventorySummary(java.util.Set<String> warehouseCodes) {
        String filter = warehouseFilter("w.code", warehouseCodes);
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(i.available + i.reserved + i.damaged), 0) AS physical,
                   COALESCE(SUM(i.available), 0) AS available, COALESCE(SUM(i.reserved), 0) AS reserved,
                   COALESCE(SUM(i.damaged), 0) AS damaged, COALESCE(SUM(i.shipped), 0) AS shipped,
                   COALESCE(SUM(i.written_off), 0) AS written_off, COUNT(DISTINCT i.sku) AS products,
                   COUNT(DISTINCT CASE WHEN i.available + i.reserved + i.damaged > 0 THEN i.warehouse_code END) AS stocked_warehouses,
                   COUNT(CASE WHEN i.available < COALESCE(t.minimum_available, 5) THEN 1 END) AS below_threshold
            FROM inventory i JOIN warehouses w ON w.code = i.warehouse_code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE 1=1
            """ + filter, (rs, row) -> new NetworkInventorySummary(rs.getLong("physical"),
            rs.getLong("available"), rs.getLong("reserved"), rs.getLong("damaged"), rs.getLong("shipped"),
            rs.getLong("written_off"), rs.getLong("products"), rs.getLong("stocked_warehouses"),
            rs.getLong("below_threshold")), warehouseCodes == null ? new Object[0] : warehouseCodes.toArray());
    }

    public WarehouseInventorySummary warehouseInventorySummary(String warehouseCode) {
        return jdbc.query("""
            SELECT w.code, w.name, w.status,
                   COALESCE(SUM(i.available + i.reserved + i.damaged), 0) AS physical,
                   COALESCE(SUM(i.available), 0) AS available, COALESCE(SUM(i.reserved), 0) AS reserved,
                   COALESCE(SUM(i.damaged), 0) AS damaged, COALESCE(SUM(i.shipped), 0) AS shipped,
                   COALESCE(SUM(i.written_off), 0) AS written_off, COUNT(i.sku) AS products,
                   COUNT(CASE WHEN i.available < COALESCE(t.minimum_available, 5) THEN 1 END) AS low_products
            FROM warehouses w LEFT JOIN inventory i ON i.warehouse_code = w.code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE w.code = ? GROUP BY w.code, w.name, w.status
            """, (rs, row) -> new WarehouseInventorySummary(rs.getString("code"), rs.getString("name"),
            WarehouseStatus.valueOf(rs.getString("status")), rs.getLong("physical"), rs.getLong("available"),
            rs.getLong("reserved"), rs.getLong("damaged"), rs.getLong("shipped"), rs.getLong("written_off"),
            rs.getLong("products"), rs.getLong("low_products")), warehouseCode).stream().findFirst()
            .orElseThrow(() -> ApiProblem.notFound("That warehouse does not exist."));
    }

    private String warehouseFilter(String column, java.util.Set<String> codes) {
        if (codes == null) return "";
        if (codes.isEmpty()) return " AND 1=0";
        return " AND " + column + " IN (" + String.join(",", java.util.Collections.nCopies(codes.size(), "?")) + ")";
    }

    public List<ProductInventory> productInventory() { return productInventory(null, null); }

    public List<ProductInventory> productInventory(java.util.Set<String> warehouseCodes) {
        return productInventory(warehouseCodes, null);
    }

    public List<ProductInventory> productInventoryForSku(String sku, java.util.Set<String> warehouseCodes) {
        return productInventory(warehouseCodes, sku);
    }

    private List<ProductInventory> productInventory(java.util.Set<String> warehouseCodes, String sku) {
        String sql = """
            SELECT p.sku, p.name AS product, w.code AS warehouse_code, w.name AS warehouse,
                   COALESCE(i.available, 0) AS available, COALESCE(i.reserved, 0) AS reserved,
                   COALESCE(i.damaged, 0) AS damaged, COALESCE(i.shipped, 0) AS shipped,
                   COALESCE(i.written_off, 0) AS written_off,
                   COALESCE(t.minimum_available, 5) AS minimum_available, w.status AS warehouse_status,
                   w.priority
            FROM products p CROSS JOIN warehouses w
            LEFT JOIN inventory i ON i.sku = p.sku AND i.warehouse_code = w.code
            LEFT JOIN stock_thresholds t ON t.sku = p.sku AND t.warehouse_code = w.code
            WHERE 1=1
            """;
        List<Object> args = new java.util.ArrayList<>();
        if (sku != null) { sql += " AND p.sku = ?"; args.add(sku); }
        if (warehouseCodes != null) {
            if (warehouseCodes.isEmpty()) return List.of();
            sql += " AND w.code IN (" + String.join(",", java.util.Collections.nCopies(warehouseCodes.size(), "?")) + ")";
            args.addAll(warehouseCodes);
        }
        sql += " ORDER BY p.name, p.sku, w.priority, w.code";
        List<ProductWarehouseRow> rows = jdbc.query(sql, (rs, row) -> new ProductWarehouseRow(rs.getString("sku"),
            rs.getString("product"), rs.getString("warehouse_code"), rs.getString("warehouse"),
            rs.getLong("available"), rs.getLong("reserved"), rs.getLong("damaged"), rs.getLong("shipped"),
            rs.getLong("written_off"), rs.getLong("minimum_available"),
            WarehouseStatus.valueOf(rs.getString("warehouse_status")), rs.getInt("priority")), args.toArray());
        Map<String, ProductInventoryAccumulator> products = new java.util.LinkedHashMap<>();
        for (ProductWarehouseRow row : rows) {
            products.computeIfAbsent(row.sku(), ignored -> new ProductInventoryAccumulator(row.sku(), row.product()))
                .add(row);
        }
        return products.values().stream().map(ProductInventoryAccumulator::result).toList();
    }

    public List<Stock> stockForSku(String sku) {
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available
            FROM inventory i JOIN warehouses w ON i.warehouse_code = w.code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.sku = ? ORDER BY w.priority, w.code
            """, STOCK, sku);
    }

    public List<AllocationCandidate> allocationCandidates(List<String> skus) {
        if (skus.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(skus.size(), "?"));
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available,
                   w.code AS warehouse_code, w.name AS warehouse_name, w.city, w.priority, w.status,
                   w.latitude, w.longitude, w.handling_cost, w.shipping_cost_per_unit,
                   w.max_fulfillment_capacity
            FROM inventory i JOIN warehouses w ON w.code = i.warehouse_code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.sku IN (
            """ + marks + ") ORDER BY i.sku, w.priority, w.code", (rs, row) -> {
                Stock stock = STOCK.mapRow(rs, row);
                Warehouse location = new Warehouse(rs.getString("warehouse_code"), rs.getString("warehouse_name"),
                    rs.getString("city"), rs.getInt("priority"), WarehouseStatus.valueOf(rs.getString("status")),
                    rs.getBigDecimal("latitude") == null ? null : rs.getBigDecimal("latitude").doubleValue(),
                    rs.getBigDecimal("longitude") == null ? null : rs.getBigDecimal("longitude").doubleValue(),
                    rs.getBigDecimal("handling_cost"), rs.getBigDecimal("shipping_cost_per_unit"),
                    rs.getObject("max_fulfillment_capacity", Integer.class));
                return new AllocationCandidate(stock, location);
            }, skus.toArray());
    }

    public void lockWarehouseConfigurations(List<String> warehouseCodes) {
        if (warehouseCodes.isEmpty()) return;
        List<String> codes = warehouseCodes.stream().distinct().sorted().toList();
        String marks = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        String product = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        String lock = product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
            ? " FOR SHARE" : " FOR UPDATE";
        long started = System.nanoTime();
        try {
            jdbc.query("SELECT code FROM warehouses WHERE code IN (" + marks + ") ORDER BY code" + lock,
                (rs, row) -> rs.getString(1), codes.toArray());
        } finally { metrics.lockWait(System.nanoTime() - started); }
    }

    public Warehouse warehouse(String code) {
        return jdbc.query("SELECT * FROM warehouses WHERE code = ?", (rs, row) -> warehouse(rs), code)
            .stream().findFirst().orElseThrow(() -> ApiProblem.notFound("That warehouse does not exist."));
    }

    public void updateWarehouseConfiguration(String code, WarehouseStatus status, Double latitude, Double longitude,
            BigDecimal handlingCost, BigDecimal shippingCostPerUnit, Integer maxCapacity) {
        if (jdbc.update("""
            UPDATE warehouses SET status = ?, latitude = ?, longitude = ?, handling_cost = ?,
                shipping_cost_per_unit = ?, max_fulfillment_capacity = ? WHERE code = ?
            """, status.name(), latitude, longitude, handlingCost, shippingCostPerUnit, maxCapacity, code) == 0)
            throw ApiProblem.notFound("That warehouse does not exist.");
    }

    public List<LowStockAlert> lowStockAlerts(java.util.Set<String> warehouseCodes, boolean includeAtThreshold) {
        if (includeAtThreshold) return lowStockAlerts(warehouseCodes);
        return lowStockAlerts(warehouseCodes).stream().filter(alert -> alert.available() < alert.minimumAvailable()).toList();
    }

    public AllocationStrategy defaultAllocationStrategy() {
        return jdbc.queryForObject("SELECT setting_value FROM allocation_settings WHERE setting_key = 'DEFAULT_STRATEGY'",
            (rs, row) -> AllocationStrategy.valueOf(rs.getString(1)));
    }

    public void setDefaultAllocationStrategy(AllocationStrategy strategy, OffsetDateTime now) {
        jdbc.update("UPDATE allocation_settings SET setting_value = ?, updated_at = ? WHERE setting_key = 'DEFAULT_STRATEGY'",
            strategy.name(), now);
    }

    public boolean warehouseExists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM warehouses WHERE code = ?)", Boolean.class, code));
    }

    public List<Stock> stockForSkuForUpdate(String sku) {
        return jdbc.query("""
            SELECT i.*, COALESCE((SELECT t.minimum_available FROM stock_thresholds t
                WHERE t.sku = i.sku AND t.warehouse_code = i.warehouse_code), 5) AS minimum_available
            FROM inventory i WHERE i.sku = ? ORDER BY i.sku, i.warehouse_code FOR UPDATE
            """, STOCK, sku);
    }

    public void updateStockAbsolute(Stock before, long available, long reserved) {
        // Deliberately unsafe baseline; reachable only through the isolated concurrency simulator.
        jdbc.update("""
            UPDATE inventory SET available = ?, reserved = ?, version = version + 1
            WHERE sku = ? AND warehouse_code = ?
            """, available, reserved, before.sku(), before.warehouseCode());
    }

    public List<InventoryMovement> movements(String search, String warehouseCode, MovementType movementType,
                                      OffsetDateTime from, OffsetDateTime to, UUID orderId,
                                      int limit, int offset) {
        return movements(search, warehouseCode, movementType, from, to, orderId, limit, offset, null);
    }

    public List<InventoryMovement> movements(String search, String warehouseCode, MovementType movementType,
                                      OffsetDateTime from, OffsetDateTime to, UUID orderId,
                                      int limit, int offset, java.util.Set<String> warehouseScope) {
        StringBuilder sql = new StringBuilder("""
            SELECT m.* FROM inventory_movements m
            JOIN products p ON p.sku = m.product_id
            WHERE 1 = 1
            """);
        List<Object> parameters = new java.util.ArrayList<>();
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(m.product_id) LIKE ? OR LOWER(p.name) LIKE ?)");
            parameters.add("%" + search.strip().toLowerCase() + "%");
            parameters.add("%" + search.strip().toLowerCase() + "%");
        }
        if (warehouseCode != null) { sql.append(" AND m.warehouse_id = ?"); parameters.add(warehouseCode); }
        if (warehouseScope != null) {
            if (warehouseScope.isEmpty()) return List.of();
            sql.append(" AND m.warehouse_id IN (")
                .append(String.join(",", java.util.Collections.nCopies(warehouseScope.size(), "?"))).append(")");
            parameters.addAll(warehouseScope.stream().sorted().toList());
        }
        if (movementType != null) { sql.append(" AND m.movement_type = ?"); parameters.add(movementType.name()); }
        if (from != null) { sql.append(" AND m.occurred_at >= ?"); parameters.add(from); }
        if (to != null) { sql.append(" AND m.occurred_at < ?"); parameters.add(to); }
        if (orderId != null) { sql.append(" AND m.reference_order_id = ?"); parameters.add(orderId); }
        sql.append(" ORDER BY m.occurred_at DESC, m.movement_sequence DESC, m.movement_id LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query(sql.toString(), MOVEMENT, parameters.toArray());
    }

    public List<LowStockAlert> lowStockAlerts() { return lowStockAlerts(null); }

    public List<LowStockAlert> lowStockAlerts(java.util.Set<String> warehouseCodes) {
        String sql = """
            SELECT i.sku, p.name AS product, i.warehouse_code, w.name AS warehouse, i.available,
                   COALESCE(t.minimum_available, 5) AS minimum_available,
                   GREATEST(0, COALESCE(t.minimum_available, 5) - i.available) AS replenishment_units
            FROM inventory i
            JOIN products p ON p.sku = i.sku
            JOIN warehouses w ON w.code = i.warehouse_code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.available < COALESCE(t.minimum_available, 5)
            """;
        List<Object> args = new java.util.ArrayList<>();
        if (warehouseCodes != null) {
            if (warehouseCodes.isEmpty()) return List.of();
            sql += " AND w.code IN (" + String.join(",", java.util.Collections.nCopies(warehouseCodes.size(), "?")) + ")";
            args.addAll(warehouseCodes);
        }
        sql += """
            ORDER BY i.available ASC, p.name, w.priority, w.code
            """;
        return jdbc.query(sql, (rs, row) -> new LowStockAlert(rs.getString("sku"), rs.getString("product"),
            rs.getString("warehouse_code"), rs.getString("warehouse"), rs.getLong("available"),
            rs.getLong("minimum_available"), rs.getLong("replenishment_units"), rs.getLong("available") == 0),
            args.toArray());
    }

    public java.util.Map<String, Long> movementBucketTotals() {
        return jdbc.query("""
            SELECT product_id, warehouse_id, stock_bucket, COALESCE(SUM(quantity_change), 0) AS quantity
            FROM inventory_movements GROUP BY product_id, warehouse_id, stock_bucket
            """, rs -> {
                var totals = new java.util.HashMap<String, Long>();
                while (rs.next()) {
                    totals.put(rs.getString("product_id") + "|" + rs.getString("warehouse_id") + "|"
                        + rs.getString("stock_bucket"), rs.getLong("quantity"));
                }
                return totals;
            });
    }

    public List<InventoryMovement> allMovementsForIntegrity() {
        return jdbc.query("""
            SELECT * FROM inventory_movements
            ORDER BY product_id, warehouse_id, stock_bucket, occurred_at, movement_id
            """, MOVEMENT);
    }

    public Map<UUID, String> operationTypes() {
        return jdbc.query("SELECT operation_id, operation_type FROM inventory_operations", rs -> {
            var types = new java.util.HashMap<UUID, String>();
            while (rs.next()) types.put(rs.getObject("operation_id", UUID.class), rs.getString("operation_type"));
            return types;
        });
    }

    public Set<UUID> movementsWithMissingOperations() {
        return Set.copyOf(jdbc.query("""
            SELECT m.movement_id FROM inventory_movements m
            LEFT JOIN inventory_operations o ON o.operation_id = m.operation_id
            WHERE o.operation_id IS NULL
            """, (rs, row) -> rs.getObject("movement_id", UUID.class)));
    }

    public java.util.Map<String, String> productWarehouseNames() {
        return jdbc.query("""
            SELECT i.sku, p.name AS product, i.warehouse_code, w.name AS warehouse
            FROM inventory i JOIN products p ON p.sku = i.sku JOIN warehouses w ON w.code = i.warehouse_code
            """, rs -> {
                var names = new java.util.HashMap<String, String>();
                while (rs.next()) {
                    names.put(rs.getString("sku") + "|" + rs.getString("warehouse_code"),
                        rs.getString("product") + "|" + rs.getString("warehouse"));
                }
                return names;
            });
    }

    public Stock stock(String sku, String warehouse) {
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available
            FROM inventory i LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.sku = ? AND i.warehouse_code = ?
            """, STOCK, sku, warehouse)
            .stream().findFirst().orElseThrow(() -> ApiProblem.notFound("That SKU or warehouse does not exist."));
    }

    public boolean updateStock(Stock before, long availableDelta, long reservedDelta, long damagedDelta,
                        long shippedDelta, long writtenOffDelta) {
        return jdbc.update("""
            UPDATE inventory SET available = available + ?, reserved = reserved + ?, damaged = damaged + ?,
                shipped = shipped + ?, written_off = written_off + ?, version = version + 1
            WHERE sku = ? AND warehouse_code = ? AND version = ?
            """, availableDelta, reservedDelta, damagedDelta, shippedDelta, writtenOffDelta,
            before.sku(), before.warehouseCode(),
            before.version()) == 1;
    }

    public void insertMovement(InventoryMovement movement) {
        jdbc.update("""
            INSERT INTO inventory_movements (movement_id, product_id, warehouse_id, movement_type, stock_bucket,
                quantity_change, previous_stock, new_stock, reference_order_id, source_warehouse,
                destination_warehouse, reason, actor, occurred_at, operation_id, actor_user_id, actor_role)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, movement.movementId(), movement.productId(), movement.warehouseId(), movement.movementType().name(),
            movement.stockBucket().name(), movement.quantityChange(), movement.previousStock(), movement.newStock(),
            movement.referenceOrderId(), movement.sourceWarehouse(), movement.destinationWarehouse(), movement.reason(),
            movement.actor(), movement.timestamp(), movement.operationId(), movement.actorUserId(), movement.actorRole());
    }

    public void insertOperation(UUID operationId, UUID requestKey, String type, String payloadHash, OffsetDateTime now) {
        jdbc.update("INSERT INTO inventory_operations VALUES (?, ?, ?, ?, ?)", operationId, requestKey, type, payloadHash, now);
    }

    public int returnedQuantity(UUID orderId, String sku, String warehouseCode) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(-quantity_change), 0) FROM inventory_movements
            WHERE reference_order_id = ? AND product_id = ? AND warehouse_id = ?
              AND movement_type = 'RETURN' AND stock_bucket = 'SHIPPED'
            """, Integer.class, orderId, sku, warehouseCode);
    }

    public Optional<InventoryOperation> operationByRequestKey(UUID requestKey) {
        return jdbc.query("SELECT * FROM inventory_operations WHERE request_key = ?", OPERATION, requestKey)
            .stream().findFirst();
    }

    public boolean hasOperation(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM inventory_operations WHERE operation_id = ?",
            Boolean.class, operationId));
    }

    public boolean hasMovements(String sku, String warehouseCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM inventory_movements
            WHERE product_id = ? AND warehouse_id = ?
            """, Boolean.class, sku, warehouseCode));
    }

    public void setThreshold(String sku, String warehouseCode, int minimumAvailable) {
        int changed = jdbc.update("UPDATE stock_thresholds SET minimum_available = ? WHERE sku = ? AND warehouse_code = ?",
            minimumAvailable, sku, warehouseCode);
        if (changed == 0) {
            jdbc.update("INSERT INTO stock_thresholds VALUES (?, ?, ?)", sku, warehouseCode, minimumAvailable);
        }
    }

    public void insertOrder(UUID id, UUID key, String fingerprint, String customer, OffsetDateTime now) {
        jdbc.update("""
            INSERT INTO customer_orders (id, request_key, fingerprint, customer, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'RESERVED', ?, ?)
            """, id, key, fingerprint, customer, now, now);
    }

    public Optional<OrderHeader> byKey(UUID key) {
        return jdbc.query("SELECT * FROM customer_orders WHERE request_key = ?", ORDER, key).stream().findFirst();
    }

    public OrderHeader orderHeader(UUID id) {
        return jdbc.query("SELECT * FROM customer_orders WHERE id = ?", ORDER, id)
            .stream().findFirst().orElseThrow(() -> ApiProblem.notFound("Order does not exist."));
    }

    public void insertAllocation(UUID id, Allocation allocation) {
        insertAllocation(id, allocation, "LEGACY_PRIORITY", null, null,
            "Allocation created by the isolated legacy concurrency simulation.",
            Map.of("schemaVersion", 1, "simulationOrLegacy", true), OffsetDateTime.now());
    }

    public void insertAllocation(UUID id, Allocation allocation, AllocationStrategy strategy,
                                 BigDecimal estimatedCost, BigDecimal estimatedDistanceKm, String reason,
                                 Map<String, ?> metadata, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO allocations (order_id, sku, warehouse_code, quantity) VALUES (?, ?, ?, ?)",
            id, allocation.sku(), allocation.warehouseCode(), allocation.quantity());
        String json;
        try { json = JSON.writeValueAsString(metadata); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Allocation decision could not be serialized.", failure);
        }
        jdbc.update("""
            INSERT INTO allocation_decisions(order_id, sku, warehouse_code, strategy, status,
                estimated_cost, estimated_distance_km, reason, decision_metadata, created_at)
            VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?, ?)
            """, id, allocation.sku(), allocation.warehouseCode(), strategy.name(), estimatedCost,
            estimatedDistanceKm, reason, json, createdAt);
    }

    private void insertAllocation(UUID id, Allocation allocation, String strategy, BigDecimal cost,
                                  BigDecimal distance, String reason, Map<String, ?> metadata,
                                  OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO allocations (order_id, sku, warehouse_code, quantity) VALUES (?, ?, ?, ?)",
            id, allocation.sku(), allocation.warehouseCode(), allocation.quantity());
        String json;
        try { json = JSON.writeValueAsString(metadata); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Allocation decision could not be serialized.", failure);
        }
        jdbc.update("""
            INSERT INTO allocation_decisions(order_id, sku, warehouse_code, strategy, status,
                estimated_cost, estimated_distance_km, reason, decision_metadata, created_at)
            VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?, ?)
            """, id, allocation.sku(), allocation.warehouseCode(), strategy, cost,
            distance, reason, json, createdAt);
    }

    public List<AllocationDecision> allocationDecisions(UUID orderId) {
        return jdbc.query("""
            SELECT allocation_id, order_id, sku, warehouse_code, strategy, status, estimated_cost,
                   estimated_distance_km, reason, decision_metadata, created_at
            FROM allocation_decisions WHERE order_id = ? ORDER BY sku, warehouse_code
            """, (rs, row) -> new AllocationDecision(rs.getLong("allocation_id"),
            rs.getObject("order_id", UUID.class), rs.getString("sku"), rs.getString("warehouse_code"),
            rs.getString("strategy"), rs.getString("status"), rs.getBigDecimal("estimated_cost"),
            rs.getBigDecimal("estimated_distance_km"), rs.getString("reason"),
            rs.getString("decision_metadata"), rs.getObject("created_at", OffsetDateTime.class)), orderId);
    }

    public List<Allocation> allocations(UUID id) {
        return jdbc.query("SELECT * FROM allocations WHERE order_id = ? ORDER BY sku, warehouse_code",
            ALLOCATION, id);
    }

    public List<Order> orders(OrderStatus status, int limit, int offset) {
        return orders(status, limit, offset, null);
    }

    public List<Order> orders(OrderStatus status, int limit, int offset, java.util.Set<String> warehouseCodes) {
        if (warehouseCodes != null && warehouseCodes.isEmpty()) return List.of();
        StringBuilder query = new StringBuilder("SELECT o.* FROM customer_orders o WHERE 1 = 1");
        List<Object> parameters = new java.util.ArrayList<>();
        if (status != null) { query.append(" AND o.status = ?"); parameters.add(status.name()); }
        if (warehouseCodes != null) {
            List<String> sortedCodes = warehouseCodes.stream().sorted().toList();
            query.append(" AND NOT EXISTS (SELECT 1 FROM allocations a WHERE a.order_id = o.id AND a.warehouse_code NOT IN (")
                .append(String.join(",", java.util.Collections.nCopies(sortedCodes.size(), "?"))).append("))");
            parameters.addAll(sortedCodes);
        }
        query.append(" ORDER BY o.created_at DESC, o.id LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        List<OrderHeader> headers = jdbc.query(query.toString(), ORDER, parameters.toArray());
        if (headers.isEmpty()) return List.of();

        String placeholders = String.join(",", java.util.Collections.nCopies(headers.size(), "?"));
        Map<UUID, List<Allocation>> allocationsByOrder = new java.util.HashMap<>();
        String sql = "SELECT * FROM allocations WHERE order_id IN (" + placeholders
            + ") ORDER BY order_id, sku, warehouse_code";
        jdbc.query(sql, rs -> {
            UUID orderId = rs.getObject("order_id", UUID.class);
            Allocation allocation = ALLOCATION.mapRow(rs, 0);
            allocationsByOrder.computeIfAbsent(orderId, ignored -> new java.util.ArrayList<>()).add(allocation);
        }, headers.stream().map(OrderHeader::id).toArray());
        return headers.stream().map(header -> header.withAllocations(
            allocationsByOrder.getOrDefault(header.id(), List.of()))).toList();
    }

    public boolean transition(UUID id, OrderStatus next, OffsetDateTime now) {
        boolean updated = jdbc.update("""
            UPDATE customer_orders SET status = ?, updated_at = ? WHERE id = ? AND status = 'RESERVED'
            """, next.name(), now, id) == 1;
        if (updated) jdbc.update("UPDATE allocation_decisions SET status = ? WHERE order_id = ? AND status = 'RESERVED'",
            next.name(), id);
        return updated;
    }

    public void enqueue(UUID id, OffsetDateTime dueAt) {
        enqueueEvent(UUID.randomUUID(), EventType.ORDER_RESERVED, "ORDER", id.toString(),
            Map.of("schemaVersion", 1, "orderId", id), id, "ORDER_RESERVED:" + id,
            dueAt.minusSeconds(1), dueAt);
    }

    public void enqueueEvent(UUID eventId, EventType type, String aggregateType, String aggregateId,
                             Map<String, ?> payload, UUID correlationId, String dedupeKey,
                             OffsetDateTime createdAt, OffsetDateTime nextAttemptAt) {
        enqueueEvent(eventId, type, aggregateType, aggregateId, payload, correlationId, dedupeKey,
            createdAt, nextAttemptAt, CorrelationContext.current());
    }

    public void enqueueEvent(UUID eventId, EventType type, String aggregateType, String aggregateId,
                             Map<String, ?> payload, UUID correlationId, String dedupeKey,
                             OffsetDateTime createdAt, OffsetDateTime nextAttemptAt, UUID requestCorrelationId) {
        String serialized;
        try { serialized = JSON.writeValueAsString(payload); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Event payload could not be serialized.", failure);
        }
        jdbc.update("""
            INSERT INTO outbox_events(event_id, event_type, aggregate_type, aggregate_id, payload, created_at,
                status, attempt_count, next_attempt_at, correlation_id, dedupe_key, request_correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
            """, eventId, type.name(), aggregateType, aggregateId, serialized, createdAt,
            nextAttemptAt, correlationId, dedupeKey, requestCorrelationId);
    }

    public List<OutboxEvent> claimEvents(String workerId, int limit, OffsetDateTime now, Duration lease) {
        return eventTransaction.execute(status -> {
            String product = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
            String lockMode = product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE";
            List<OutboxEvent> candidates = jdbc.query("""
                SELECT * FROM outbox_events
                WHERE ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= ?)
                    OR (status = 'PROCESSING' AND lease_until <= ?))
                ORDER BY next_attempt_at, created_at, event_id LIMIT ?
                """ + lockMode, EVENT, now, now, limit);
            List<OutboxEvent> claimed = new java.util.ArrayList<>();
            for (OutboxEvent event : candidates) {
                if ("PROCESSING".equals(event.status())) {
                    jdbc.update("""
                        UPDATE outbox_event_attempts SET outcome = 'ABANDONED', completed_at = ?,
                            error_message = 'Processing lease expired.'
                        WHERE event_id = ? AND outcome = 'STARTED'
                        """, now, event.eventId());
                    metrics.afterCommit(metrics::leaseRecovered);
                }
                int attempt = event.attemptCount() + 1;
                jdbc.update("""
                    UPDATE outbox_events SET status = 'PROCESSING', attempt_count = ?, claimed_by = ?,
                        processing_at = ?, lease_until = ?, last_error = NULL, failure_kind = NULL
                    WHERE event_id = ?
                    """, attempt, workerId, now, now.plus(lease), event.eventId());
                jdbc.update("""
                    INSERT INTO outbox_event_attempts(attempt_id, event_id, attempt_number, worker_id,
                        started_at, outcome) VALUES (?, ?, ?, ?, ?, 'STARTED')
                    """, UUID.randomUUID(), event.eventId(), attempt, workerId, now);
                claimed.add(event(event.eventId()).orElseThrow());
            }
            return List.copyOf(claimed);
        });
    }

    public boolean completeEvent(UUID eventId, String workerId, OffsetDateTime now) {
        return eventTransaction.execute(status -> {
            int changed = jdbc.update("""
                UPDATE outbox_events SET status = 'COMPLETED', processed_at = ?, claimed_by = NULL,
                    lease_until = NULL, last_error = NULL, failure_kind = NULL
                WHERE event_id = ? AND status = 'PROCESSING' AND claimed_by = ?
                """, now, eventId, workerId);
            if (changed == 1) jdbc.update("""
                UPDATE outbox_event_attempts SET outcome = 'COMPLETED', completed_at = ?
                WHERE event_id = ? AND worker_id = ? AND outcome = 'STARTED'
                """, now, eventId, workerId);
            return changed == 1;
        });
    }

    public boolean failEvent(UUID eventId, String workerId, OffsetDateTime now, OffsetDateTime nextAttempt,
                             int maxAttempts, String failureKind, String message) {
        return eventTransaction.execute(status -> {
            OutboxEvent event = event(eventId).orElse(null);
            if (event == null || !"PROCESSING".equals(event.status()) || !workerId.equals(event.claimedBy())) return false;
            boolean dead = "PERMANENT".equals(failureKind) || event.attemptCount() >= maxAttempts;
            String outcome = dead ? "DEAD" : "RETRY";
            jdbc.update("""
                UPDATE outbox_event_attempts SET outcome = ?, completed_at = ?, error_message = ?
                WHERE event_id = ? AND worker_id = ? AND outcome = 'STARTED'
                """, outcome, now, message, eventId, workerId);
            return jdbc.update("""
                UPDATE outbox_events SET status = ?, next_attempt_at = ?, last_error = ?, failure_kind = ?,
                    claimed_by = NULL, lease_until = NULL
                WHERE event_id = ? AND status = 'PROCESSING' AND claimed_by = ?
                """, dead ? "DEAD" : "RETRY_WAIT", dead ? now : nextAttempt, message,
                failureKind, eventId, workerId) == 1;
        });
    }

    public boolean retryDeadEvent(UUID id, OffsetDateTime now) {
        return jdbc.update("""
            UPDATE outbox_events SET status = 'RETRY_WAIT', attempt_count = 0, next_attempt_at = ?,
                last_error = NULL, failure_kind = NULL, processing_at = NULL, claimed_by = NULL, lease_until = NULL
            WHERE event_id = ? AND status = 'DEAD' AND failure_kind = 'RETRYABLE'
            """, now, id) == 1;
    }

    public void completeUnclaimedEvent(String dedupeKey, OffsetDateTime now) {
        jdbc.update("""
            UPDATE outbox_events SET status = 'COMPLETED', processed_at = ?, last_error = NULL
            WHERE dedupe_key = ? AND status IN ('PENDING','RETRY_WAIT')
            """, now, dedupeKey);
    }

    public Optional<OutboxEvent> event(UUID eventId) {
        return jdbc.query("SELECT * FROM outbox_events WHERE event_id = ?", EVENT, eventId).stream().findFirst();
    }

    public List<OutboxEvent> events(String eventType, String status, UUID orderId, int limit, int offset) {
        return events(eventType, status, orderId, null, limit, offset);
    }

    public List<OutboxEvent> events(String eventType, String status, UUID orderId, String search, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM outbox_events WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (eventType != null) { sql.append(" AND event_type = ?"); args.add(eventType); }
        if (status != null) { sql.append(" AND status = ?"); args.add(status); }
        if (orderId != null) { sql.append(" AND aggregate_type = 'ORDER' AND aggregate_id = ?"); args.add(orderId.toString()); }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase() + "%";
            sql.append(" AND (LOWER(event_type) LIKE ? OR LOWER(aggregate_id) LIKE ? OR LOWER(CAST(event_id AS VARCHAR)) LIKE ?)");
            args.add(term); args.add(term); args.add(term);
        }
        sql.append(" ORDER BY created_at DESC, event_id LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), EVENT, args.toArray());
    }

    public List<OutboxEvent> events() { return events(null, null, null, 50, 0); }

    public long readyEventCount(OffsetDateTime now) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= ?)
               OR (status = 'PROCESSING' AND lease_until <= ?)
            """, Long.class, now, now);
    }

    public List<EventAttempt> eventAttempts(UUID eventId) {
        return jdbc.query("SELECT * FROM outbox_event_attempts WHERE event_id = ? ORDER BY started_at, attempt_id", ATTEMPT, eventId);
    }

    public List<ReplenishmentSuggestion> replenishmentSuggestions(String warehouseCode) {
        String sql = """
            SELECT s.*, p.name AS product, w.name AS warehouse FROM replenishment_suggestions s
            JOIN products p ON p.sku = s.sku JOIN warehouses w ON w.code = s.warehouse_code
            WHERE s.status = 'ACTIVE'
            """ + (warehouseCode == null ? "" : " AND s.warehouse_code = ?")
            + " ORDER BY s.created_at DESC, p.name, w.code";
        return warehouseCode == null ? jdbc.query(sql, SUGGESTION) : jdbc.query(sql, SUGGESTION, warehouseCode);
    }

    public void synchronizeLowStockState(String sku, String warehouseCode, long available, long minimumAvailable,
                                        UUID correlationId, OffsetDateTime now) {
        if (available < minimumAvailable) {
            UUID eventId = UUID.randomUUID();
            int activated = jdbc.update("""
                UPDATE low_stock_event_state SET is_low = TRUE, active_event_id = ?, updated_at = ?
                WHERE sku = ? AND warehouse_code = ? AND is_low = FALSE
                """, eventId, now, sku, warehouseCode);
            if (activated == 1) {
                enqueueEvent(eventId, EventType.LOW_STOCK, "INVENTORY", sku + ":" + warehouseCode,
                    Map.of("schemaVersion", 1, "sku", sku, "warehouseCode", warehouseCode,
                        "available", available, "minimumAvailable", minimumAvailable,
                        "recommendedQuantity", Math.max(0, minimumAvailable - available)),
                    correlationId, "LOW_STOCK:" + sku + ":" + warehouseCode + ":" + eventId, now, now);
            }
            return;
        }
        UUID activeEvent = jdbc.query("SELECT active_event_id FROM low_stock_event_state WHERE sku = ? AND warehouse_code = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, sku, warehouseCode);
        int cleared = jdbc.update("""
            UPDATE low_stock_event_state SET is_low = FALSE, active_event_id = NULL, updated_at = ?
            WHERE sku = ? AND warehouse_code = ? AND is_low = TRUE
            """, now, sku, warehouseCode);
        if (cleared == 1 && activeEvent != null) jdbc.update("""
            UPDATE replenishment_suggestions SET status = 'CLEARED', updated_at = ?
            WHERE event_id = ? AND status = 'ACTIVE'
            """, now, activeEvent);
    }

    public void ensureLowStockState(String sku, String warehouseCode, OffsetDateTime now) {
        jdbc.update("""
            INSERT INTO low_stock_event_state(sku, warehouse_code, is_low, updated_at)
            SELECT ?, ?, FALSE, ? WHERE NOT EXISTS (SELECT 1 FROM low_stock_event_state WHERE sku = ? AND warehouse_code = ?)
            """, sku, warehouseCode, now, sku, warehouseCode);
    }

    public boolean saveReplenishmentSuggestion(OutboxEvent event, String sku, String warehouseCode,
                                                long available, long minimumAvailable, long recommendedQuantity,
                                                OffsetDateTime now) {
        UUID activeEvent = jdbc.query("SELECT active_event_id FROM low_stock_event_state WHERE sku = ? AND warehouse_code = ? AND is_low = TRUE",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, sku, warehouseCode);
        if (!event.eventId().equals(activeEvent)) return true;
        return jdbc.update("""
            INSERT INTO replenishment_suggestions(suggestion_id, event_id, sku, warehouse_code, available,
                minimum_available, recommended_quantity, status, created_at, updated_at)
            SELECT ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM replenishment_suggestions WHERE event_id = ?)
            """, event.eventId(), event.eventId(), sku, warehouseCode, available, minimumAvailable,
            recommendedQuantity, now, now, event.eventId()) <= 1;
    }

    public boolean dismissSuggestion(UUID suggestionId, String warehouseCode, OffsetDateTime now) {
        return jdbc.update("""
            UPDATE replenishment_suggestions SET status = 'DISMISSED', updated_at = ?
            WHERE suggestion_id = ? AND status = 'ACTIVE' AND warehouse_code = ?
            """, now, suggestionId, warehouseCode) == 1;
    }

    public Optional<ReplenishmentSuggestion> suggestion(UUID suggestionId) {
        return jdbc.query("""
            SELECT s.*, p.name AS product, w.name AS warehouse FROM replenishment_suggestions s
            JOIN products p ON p.sku = s.sku JOIN warehouses w ON w.code = s.warehouse_code
            WHERE s.suggestion_id = ?
            """, SUGGESTION, suggestionId).stream().findFirst();
    }

    public EventMetrics eventMetrics(OffsetDateTime now) {
        Map<String, Long> counts = jdbc.query("SELECT status, COUNT(*) AS amount FROM outbox_events GROUP BY status", rs -> {
            var values = new java.util.HashMap<String, Long>();
            while (rs.next()) values.put(rs.getString("status"), rs.getLong("amount"));
            return values;
        });
        List<Long> millis = jdbc.query("SELECT started_at, completed_at FROM outbox_event_attempts "
                + "WHERE completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT 1000",
            (rs, row) -> Duration.between(rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class)).toMillis());
        double average = millis.stream().mapToLong(Long::longValue).average().orElse(0);
        OffsetDateTime oldest = jdbc.query("SELECT MIN(created_at) FROM outbox_events WHERE status IN ('PENDING','RETRY_WAIT')",
            rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null);
        return new EventMetrics(counts.getOrDefault("PENDING", 0L), counts.getOrDefault("PROCESSING", 0L),
            counts.getOrDefault("RETRY_WAIT", 0L), counts.getOrDefault("COMPLETED", 0L),
            counts.getOrDefault("DEAD", 0L), jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event_attempts", Long.class),
            average, oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis()));
    }

    public Overview overview() { return overview(null); }

    /** Returns dashboard counts for the requested warehouse scope; null denotes the full network. */
    public Overview overview(java.util.Set<String> warehouseCodes) {
        String warehouseFilter = warehouseCodes == null ? "" : warehouseCodes.isEmpty()
            ? " AND 1 = 0" : " AND i.warehouse_code IN (" + String.join(",", java.util.Collections.nCopies(warehouseCodes.size(), "?")) + ")";
        String scopedOrderFilter = warehouseCodes == null ? "" : warehouseCodes.isEmpty()
            ? " WHERE 1 = 0" : " WHERE EXISTS (SELECT 1 FROM allocations visible WHERE visible.order_id = customer_orders.id AND visible.warehouse_code IN ("
                + String.join(",", java.util.Collections.nCopies(warehouseCodes.size(), "?")) + ")) AND NOT EXISTS (SELECT 1 FROM allocations hidden WHERE hidden.order_id = customer_orders.id AND hidden.warehouse_code NOT IN ("
                + String.join(",", java.util.Collections.nCopies(warehouseCodes.size(), "?")) + "))";
        String sql = """
            SELECT i.*, o.* FROM (
                SELECT COALESCE(SUM(i.available), 0) AS available, COALESCE(SUM(i.reserved), 0) AS reserved,
                       COALESCE(SUM(i.damaged), 0) AS damaged, COALESCE(SUM(i.shipped), 0) AS shipped,
                       COALESCE(SUM(i.written_off), 0) AS written_off,
                       COALESCE(SUM(i.available + i.reserved + i.damaged), 0) AS physical,
                       COUNT(DISTINCT i.sku) AS products,
                       COUNT(*) AS stock_rows,
                       COUNT(CASE WHEN i.available <= COALESCE(t.minimum_available, 5) THEN 1 END) AS low_rows,
                       COUNT(DISTINCT CASE WHEN i.available <= COALESCE(t.minimum_available, 5) THEN i.sku END) AS low_products
                FROM inventory i LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
                WHERE 1 = 1
            """ + warehouseFilter + """
            ) i CROSS JOIN (
                SELECT COUNT(*) AS orders, COUNT(CASE WHEN status = 'RESERVED' THEN 1 END) AS pending,
                       COUNT(CASE WHEN status = 'FULFILLED' THEN 1 END) AS fulfilled,
                       COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled
                FROM customer_orders
            """ + scopedOrderFilter + """
            ) o
            """;
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        if (warehouseCodes != null) {
            parameters.addAll(warehouseCodes);
            if (!warehouseCodes.isEmpty()) {
                parameters.addAll(warehouseCodes);
                parameters.addAll(warehouseCodes);
            }
        }
        return jdbc.queryForObject(sql, (rs, row) -> new Overview(rs.getLong("available"), rs.getLong("reserved"),
            rs.getLong("damaged"), rs.getLong("shipped"), rs.getLong("written_off"), rs.getLong("physical"),
            rs.getLong("products"), rs.getInt("stock_rows"), rs.getInt("low_rows"), rs.getInt("low_products"), rs.getLong("orders"),
            rs.getLong("pending"), rs.getLong("fulfilled"), rs.getLong("cancelled")), parameters.toArray());
    }
}
