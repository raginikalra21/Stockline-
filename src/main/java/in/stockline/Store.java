package in.stockline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static in.stockline.Models.*;

@Repository
class Store {
    private static final RowMapper<Stock> STOCK = (rs, row) -> new Stock(
        rs.getString("sku"), rs.getString("warehouse_code"), rs.getLong("available"),
        rs.getLong("reserved"), rs.getLong("damaged"), rs.getLong("shipped"),
        rs.getLong("written_off"), rs.getLong("minimum_available"), rs.getLong("version")
    );
    private record ProductWarehouseRow(String sku, String product, String warehouseCode, String warehouse,
                                       long available, long reserved, long damaged) {}
    private static final class ProductInventoryAccumulator {
        final String sku;
        final String product;
        long totalStock;
        long available;
        long reserved;
        long damaged;
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
            warehouses.add(new WarehouseBalance(row.warehouseCode(), row.warehouse(), physical,
                row.available(), row.reserved(), row.damaged(), row.available() == 0
                    ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.AVAILABLE));
        }

        ProductInventory result() {
            return new ProductInventory(sku, product, totalStock, available, reserved, damaged,
                available == 0 ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.AVAILABLE,
                List.copyOf(warehouses));
        }
    }
    private static final RowMapper<Allocation> ALLOCATION = (rs, row) -> new Allocation(
        rs.getString("sku"), rs.getString("warehouse_code"), rs.getInt("quantity")
    );

    record OrderHeader(UUID id, String customer, OrderStatus status, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, String fingerprint) {
        Order withAllocations(List<Allocation> allocations) {
            return new Order(id, customer, status, createdAt, updatedAt, allocations);
        }
    }

    private static final RowMapper<OrderHeader> ORDER = (rs, row) -> new OrderHeader(
        rs.getObject("id", UUID.class), rs.getString("customer"), OrderStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
        rs.getString("fingerprint")
    );
    private static final RowMapper<OutboxEvent> EVENT = (rs, row) -> new OutboxEvent(
        rs.getObject("order_id", UUID.class), rs.getString("event_type"), rs.getString("status"),
        rs.getObject("due_at", OffsetDateTime.class), rs.getObject("processed_at", OffsetDateTime.class)
    );
    private static final RowMapper<InventoryMovement> MOVEMENT = (rs, row) -> new InventoryMovement(
        rs.getObject("movement_id", UUID.class), rs.getString("product_id"), rs.getString("warehouse_id"),
        MovementType.valueOf(rs.getString("movement_type")), StockBucket.valueOf(rs.getString("stock_bucket")),
        rs.getLong("quantity_change"), rs.getLong("previous_stock"), rs.getLong("new_stock"),
        rs.getObject("reference_order_id", UUID.class), rs.getString("source_warehouse"),
        rs.getString("destination_warehouse"), rs.getString("reason"), rs.getString("actor"),
        rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("operation_id", UUID.class)
    );

    record InventoryOperation(UUID operationId, UUID requestKey, String operationType,
                              String payloadHash, OffsetDateTime createdAt) {}
    private static final RowMapper<InventoryOperation> OPERATION = (rs, row) -> new InventoryOperation(
        rs.getObject("operation_id", UUID.class), rs.getObject("request_key", UUID.class),
        rs.getString("operation_type"), rs.getString("payload_hash"),
        rs.getObject("created_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbc;

    Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Catalog catalog() {
        var products = jdbc.query("SELECT * FROM products ORDER BY name, sku",
            (rs, row) -> new Product(rs.getString("sku"), rs.getString("name"), rs.getString("category")));
        var warehouses = jdbc.query("SELECT * FROM warehouses ORDER BY priority, code",
            (rs, row) -> new Warehouse(rs.getString("code"), rs.getString("name"), rs.getString("city"),
                rs.getInt("priority")));
        return new Catalog(products, warehouses);
    }

    void addProduct(Product product) {
        lockCatalog();
        jdbc.update("INSERT INTO products VALUES (?, ?, ?)", product.sku(), product.name(), product.category());
        jdbc.update("INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version) "
            + "SELECT ?, code, 0, 0, 0, 0 FROM warehouses", product.sku());
        jdbc.update("INSERT INTO stock_thresholds SELECT ?, code, 5 FROM warehouses", product.sku());
    }

    void addWarehouse(Warehouse warehouse) {
        lockCatalog();
        jdbc.update("INSERT INTO warehouses VALUES (?, ?, ?, ?)",
            warehouse.code(), warehouse.name(), warehouse.city(), warehouse.priority());
        jdbc.update("INSERT INTO inventory (sku, warehouse_code, available, reserved, shipped, version) "
            + "SELECT sku, ?, 0, 0, 0, 0 FROM products", warehouse.code());
        jdbc.update("INSERT INTO stock_thresholds SELECT sku, ?, 5 FROM products", warehouse.code());
    }

    private void lockCatalog() {
        jdbc.queryForObject("SELECT id FROM catalog_guard WHERE id = 1 FOR UPDATE", Integer.class);
    }

    List<Stock> inventory() {
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available
            FROM inventory i JOIN warehouses w ON i.warehouse_code = w.code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            ORDER BY i.sku, w.priority, w.code
            """, STOCK);
    }

    List<ProductInventory> productInventory() {
        List<ProductWarehouseRow> rows = jdbc.query("""
            SELECT p.sku, p.name AS product, w.code AS warehouse_code, w.name AS warehouse,
                   COALESCE(i.available, 0) AS available, COALESCE(i.reserved, 0) AS reserved,
                   COALESCE(i.damaged, 0) AS damaged
            FROM products p CROSS JOIN warehouses w
            LEFT JOIN inventory i ON i.sku = p.sku AND i.warehouse_code = w.code
            ORDER BY p.name, p.sku, w.priority, w.code
            """, (rs, row) -> new ProductWarehouseRow(rs.getString("sku"), rs.getString("product"),
            rs.getString("warehouse_code"), rs.getString("warehouse"), rs.getLong("available"),
            rs.getLong("reserved"), rs.getLong("damaged")));
        Map<String, ProductInventoryAccumulator> products = new java.util.LinkedHashMap<>();
        for (ProductWarehouseRow row : rows) {
            products.computeIfAbsent(row.sku(), ignored -> new ProductInventoryAccumulator(row.sku(), row.product()))
                .add(row);
        }
        return products.values().stream().map(ProductInventoryAccumulator::result).toList();
    }

    List<Stock> stockForSku(String sku) {
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available
            FROM inventory i JOIN warehouses w ON i.warehouse_code = w.code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.sku = ? ORDER BY w.priority, w.code
            """, STOCK, sku);
    }

    boolean warehouseExists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM warehouses WHERE code = ?)", Boolean.class, code));
    }

    List<Stock> stockForSkuForUpdate(String sku) {
        return jdbc.query("""
            SELECT i.*, COALESCE((SELECT t.minimum_available FROM stock_thresholds t
                WHERE t.sku = i.sku AND t.warehouse_code = i.warehouse_code), 5) AS minimum_available
            FROM inventory i WHERE i.sku = ? ORDER BY i.sku, i.warehouse_code FOR UPDATE
            """, STOCK, sku);
    }

    void updateStockAbsolute(Stock before, long available, long reserved) {
        // Deliberately unsafe baseline; reachable only through the isolated concurrency simulator.
        jdbc.update("""
            UPDATE inventory SET available = ?, reserved = ?, version = version + 1
            WHERE sku = ? AND warehouse_code = ?
            """, available, reserved, before.sku(), before.warehouseCode());
    }

    List<InventoryMovement> movements(String search, String warehouseCode, MovementType movementType,
                                      OffsetDateTime from, OffsetDateTime to, UUID orderId,
                                      int limit, int offset) {
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
        if (movementType != null) { sql.append(" AND m.movement_type = ?"); parameters.add(movementType.name()); }
        if (from != null) { sql.append(" AND m.occurred_at >= ?"); parameters.add(from); }
        if (to != null) { sql.append(" AND m.occurred_at < ?"); parameters.add(to); }
        if (orderId != null) { sql.append(" AND m.reference_order_id = ?"); parameters.add(orderId); }
        sql.append(" ORDER BY m.occurred_at DESC, m.movement_sequence DESC, m.movement_id LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query(sql.toString(), MOVEMENT, parameters.toArray());
    }

    List<LowStockAlert> lowStockAlerts() {
        return jdbc.query("""
            SELECT i.sku, p.name AS product, i.warehouse_code, w.name AS warehouse, i.available,
                   COALESCE(t.minimum_available, 5) AS minimum_available,
                   GREATEST(0, COALESCE(t.minimum_available, 5) - i.available) AS replenishment_units
            FROM inventory i
            JOIN products p ON p.sku = i.sku
            JOIN warehouses w ON w.code = i.warehouse_code
            LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.available <= COALESCE(t.minimum_available, 5)
            ORDER BY i.available ASC, p.name, w.priority, w.code
            """, (rs, row) -> new LowStockAlert(rs.getString("sku"), rs.getString("product"),
            rs.getString("warehouse_code"), rs.getString("warehouse"), rs.getLong("available"),
            rs.getLong("minimum_available"), rs.getLong("replenishment_units"), rs.getLong("available") == 0));
    }

    java.util.Map<String, Long> movementBucketTotals() {
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

    List<InventoryMovement> allMovementsForIntegrity() {
        return jdbc.query("""
            SELECT * FROM inventory_movements
            ORDER BY product_id, warehouse_id, stock_bucket, occurred_at, movement_id
            """, MOVEMENT);
    }

    Map<UUID, String> operationTypes() {
        return jdbc.query("SELECT operation_id, operation_type FROM inventory_operations", rs -> {
            var types = new java.util.HashMap<UUID, String>();
            while (rs.next()) types.put(rs.getObject("operation_id", UUID.class), rs.getString("operation_type"));
            return types;
        });
    }

    Set<UUID> movementsWithMissingOperations() {
        return Set.copyOf(jdbc.query("""
            SELECT m.movement_id FROM inventory_movements m
            LEFT JOIN inventory_operations o ON o.operation_id = m.operation_id
            WHERE o.operation_id IS NULL
            """, (rs, row) -> rs.getObject("movement_id", UUID.class)));
    }

    java.util.Map<String, String> productWarehouseNames() {
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

    Stock stock(String sku, String warehouse) {
        return jdbc.query("""
            SELECT i.*, COALESCE(t.minimum_available, 5) AS minimum_available
            FROM inventory i LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            WHERE i.sku = ? AND i.warehouse_code = ?
            """, STOCK, sku, warehouse)
            .stream().findFirst().orElseThrow(() -> ApiProblem.notFound("That SKU or warehouse does not exist."));
    }

    boolean updateStock(Stock before, long availableDelta, long reservedDelta, long damagedDelta,
                        long shippedDelta, long writtenOffDelta) {
        return jdbc.update("""
            UPDATE inventory SET available = available + ?, reserved = reserved + ?, damaged = damaged + ?,
                shipped = shipped + ?, written_off = written_off + ?, version = version + 1
            WHERE sku = ? AND warehouse_code = ? AND version = ?
            """, availableDelta, reservedDelta, damagedDelta, shippedDelta, writtenOffDelta,
            before.sku(), before.warehouseCode(),
            before.version()) == 1;
    }

    void insertMovement(InventoryMovement movement) {
        jdbc.update("""
            INSERT INTO inventory_movements (movement_id, product_id, warehouse_id, movement_type, stock_bucket,
                quantity_change, previous_stock, new_stock, reference_order_id, source_warehouse,
                destination_warehouse, reason, actor, occurred_at, operation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, movement.movementId(), movement.productId(), movement.warehouseId(), movement.movementType().name(),
            movement.stockBucket().name(), movement.quantityChange(), movement.previousStock(), movement.newStock(),
            movement.referenceOrderId(), movement.sourceWarehouse(), movement.destinationWarehouse(), movement.reason(),
            movement.actor(), movement.timestamp(), movement.operationId());
    }

    void insertOperation(UUID operationId, UUID requestKey, String type, String payloadHash, OffsetDateTime now) {
        jdbc.update("INSERT INTO inventory_operations VALUES (?, ?, ?, ?, ?)", operationId, requestKey, type, payloadHash, now);
    }

    int returnedQuantity(UUID orderId, String sku, String warehouseCode) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(-quantity_change), 0) FROM inventory_movements
            WHERE reference_order_id = ? AND product_id = ? AND warehouse_id = ?
              AND movement_type = 'RETURN' AND stock_bucket = 'SHIPPED'
            """, Integer.class, orderId, sku, warehouseCode);
    }

    Optional<InventoryOperation> operationByRequestKey(UUID requestKey) {
        return jdbc.query("SELECT * FROM inventory_operations WHERE request_key = ?", OPERATION, requestKey)
            .stream().findFirst();
    }

    boolean hasOperation(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM inventory_operations WHERE operation_id = ?",
            Boolean.class, operationId));
    }

    boolean hasMovements(String sku, String warehouseCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM inventory_movements
            WHERE product_id = ? AND warehouse_id = ?
            """, Boolean.class, sku, warehouseCode));
    }

    void setThreshold(String sku, String warehouseCode, int minimumAvailable) {
        int changed = jdbc.update("UPDATE stock_thresholds SET minimum_available = ? WHERE sku = ? AND warehouse_code = ?",
            minimumAvailable, sku, warehouseCode);
        if (changed == 0) {
            jdbc.update("INSERT INTO stock_thresholds VALUES (?, ?, ?)", sku, warehouseCode, minimumAvailable);
        }
    }

    void insertOrder(UUID id, UUID key, String fingerprint, String customer, OffsetDateTime now) {
        jdbc.update("""
            INSERT INTO customer_orders (id, request_key, fingerprint, customer, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'RESERVED', ?, ?)
            """, id, key, fingerprint, customer, now, now);
    }

    Optional<OrderHeader> byKey(UUID key) {
        return jdbc.query("SELECT * FROM customer_orders WHERE request_key = ?", ORDER, key).stream().findFirst();
    }

    OrderHeader orderHeader(UUID id) {
        return jdbc.query("SELECT * FROM customer_orders WHERE id = ?", ORDER, id)
            .stream().findFirst().orElseThrow(() -> ApiProblem.notFound("Order does not exist."));
    }

    void insertAllocation(UUID id, Allocation allocation) {
        jdbc.update("INSERT INTO allocations (order_id, sku, warehouse_code, quantity) VALUES (?, ?, ?, ?)",
            id, allocation.sku(), allocation.warehouseCode(), allocation.quantity());
    }

    List<Allocation> allocations(UUID id) {
        return jdbc.query("SELECT * FROM allocations WHERE order_id = ? ORDER BY sku, warehouse_code",
            ALLOCATION, id);
    }

    List<Order> orders(OrderStatus status, int limit, int offset) {
        List<OrderHeader> headers = status == null
            ? jdbc.query("SELECT * FROM customer_orders ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                ORDER, limit, offset)
            : jdbc.query("SELECT * FROM customer_orders WHERE status = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                ORDER, status.name(), limit, offset);
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

    boolean transition(UUID id, OrderStatus next, OffsetDateTime now) {
        return jdbc.update("""
            UPDATE customer_orders SET status = ?, updated_at = ? WHERE id = ? AND status = 'RESERVED'
            """, next.name(), now, id) == 1;
    }

    void enqueue(UUID id, OffsetDateTime dueAt) {
        jdbc.update("INSERT INTO outbox VALUES (?, 'ORDER_RESERVED', 'PENDING', ?, NULL)", id, dueAt);
    }

    List<UUID> dueEvents(OffsetDateTime now) {
        return jdbc.query("""
            SELECT order_id FROM outbox WHERE status = 'PENDING' AND due_at <= ?
            ORDER BY due_at, order_id LIMIT 100
            """, (rs, row) -> rs.getObject("order_id", UUID.class), now);
    }

    void finishEvent(UUID id, String status, OffsetDateTime now) {
        int changed = jdbc.update("""
            UPDATE outbox SET status = ?, processed_at = ? WHERE order_id = ? AND status = 'PENDING'
            """, status, now, id);
        if (changed != 1) {
            throw new IllegalStateException("Missing pending event for " + id);
        }
    }

    List<OutboxEvent> events() {
        return jdbc.query("SELECT * FROM outbox ORDER BY due_at DESC, order_id LIMIT 50", EVENT);
    }

    Overview overview() {
        return jdbc.queryForObject("""
            SELECT i.*, o.* FROM (
                SELECT COALESCE(SUM(i.available), 0) AS available, COALESCE(SUM(i.reserved), 0) AS reserved,
                       COALESCE(SUM(i.damaged), 0) AS damaged, COALESCE(SUM(i.shipped), 0) AS shipped,
                       COALESCE(SUM(i.written_off), 0) AS written_off,
                       COALESCE(SUM(i.available + i.reserved + i.damaged), 0) AS physical,
                       COUNT(*) AS stock_rows,
                       COUNT(CASE WHEN i.available <= COALESCE(t.minimum_available, 5) THEN 1 END) AS low_rows,
                       COUNT(DISTINCT CASE WHEN i.available <= COALESCE(t.minimum_available, 5) THEN i.sku END) AS low_products
                FROM inventory i LEFT JOIN stock_thresholds t ON t.sku = i.sku AND t.warehouse_code = i.warehouse_code
            ) i CROSS JOIN (
                SELECT COUNT(*) AS orders, COUNT(CASE WHEN status = 'RESERVED' THEN 1 END) AS pending,
                       COUNT(CASE WHEN status = 'FULFILLED' THEN 1 END) AS fulfilled,
                       COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled
                FROM customer_orders
            ) o
            """, (rs, row) -> new Overview(rs.getLong("available"), rs.getLong("reserved"),
            rs.getLong("damaged"), rs.getLong("shipped"), rs.getLong("written_off"), rs.getLong("physical"),
            rs.getInt("stock_rows"), rs.getInt("low_rows"), rs.getInt("low_products"), rs.getLong("orders"),
            rs.getLong("pending"), rs.getLong("fulfilled"), rs.getLong("cancelled")));
    }
}
