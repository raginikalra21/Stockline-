package in.stockline;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static in.stockline.Models.*;

@RestController
@RequestMapping("/api")
class ApiController {
    private final Store store;
    private final InventoryService inventory;
    private final InventoryAuditService audit;
    private final RaceDemo demo;

    ApiController(Store store, InventoryService inventory, InventoryAuditService audit, RaceDemo demo) {
        this.store = store;
        this.inventory = inventory;
        this.audit = audit;
        this.demo = demo;
    }

    @GetMapping("/overview")
    Overview overview() {
        return store.overview();
    }

    @GetMapping("/catalog")
    Catalog catalog() {
        return store.catalog();
    }

    @PostMapping(value = "/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Product addProduct(@Valid @RequestBody ProductInput input) {
        return inventory.addProduct(input);
    }

    @PostMapping(value = "/warehouses", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Warehouse addWarehouse(@Valid @RequestBody WarehouseInput input) {
        return inventory.addWarehouse(input);
    }

    @GetMapping("/inventory")
    List<Stock> stock() {
        return store.inventory();
    }

    @GetMapping("/inventory/products")
    List<ProductInventory> productInventory() {
        return store.productInventory();
    }

    @PostMapping(value = "/inventory/restock", consumes = MediaType.APPLICATION_JSON_VALUE)
    Stock restock(@Valid @RequestBody RestockInput input) {
        return inventory.restock(input);
    }

    @PostMapping(value = "/inventory/transfer", consumes = MediaType.APPLICATION_JSON_VALUE)
    List<Stock> transfer(@Valid @RequestBody TransferInput input) {
        return inventory.transfer(input);
    }

    @PostMapping(value = "/inventory/adjustments", consumes = MediaType.APPLICATION_JSON_VALUE)
    Stock adjustStock(@Valid @RequestBody StockAdjustmentInput input) {
        return inventory.adjust(input);
    }

    @PostMapping(value = "/inventory/damaged", consumes = MediaType.APPLICATION_JSON_VALUE)
    Stock recordDamagedStock(@Valid @RequestBody DamagedStockInput input) {
        return inventory.recordDamage(input);
    }

    @PostMapping(value = "/inventory/write-offs", consumes = MediaType.APPLICATION_JSON_VALUE)
    Stock writeOffStock(@Valid @RequestBody WriteOffInput input) {
        return inventory.writeOff(input);
    }

    @PostMapping(value = "/inventory/returns", consumes = MediaType.APPLICATION_JSON_VALUE)
    Stock returnStock(@Valid @RequestBody ReturnInput input) {
        return inventory.returnStock(input);
    }

    @PostMapping(value = "/inventory/threshold", consumes = MediaType.APPLICATION_JSON_VALUE)
    Stock setStockThreshold(@Valid @RequestBody ThresholdInput input) {
        return inventory.setThreshold(input);
    }

    @GetMapping("/inventory/alerts")
    List<LowStockAlert> inventoryAlerts() {
        return audit.alerts();
    }

    @GetMapping("/inventory/reconciliation")
    List<ReconciliationLine> reconcileInventory() {
        return audit.reconcile();
    }

    @GetMapping("/inventory/movements")
    List<InventoryMovement> movements(@RequestParam(required = false) String search,
                                      @RequestParam(required = false) String warehouseCode,
                                      @RequestParam(required = false) MovementType type,
                                      @RequestParam(required = false) OffsetDateTime from,
                                      @RequestParam(required = false) OffsetDateTime to,
                                      @RequestParam(required = false) UUID orderId,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || (from != null && to != null && !from.isBefore(to))) {
            throw ApiProblem.invalid("Use a limit from 1 to 100, a non-negative offset, and an increasing date range.");
        }
        return audit.movements(search, warehouseCode, type, from, to, orderId, limit, offset);
    }

    @GetMapping("/orders")
    List<Order> orders(@RequestParam(required = false) OrderStatus status,
                       @RequestParam(defaultValue = "25") int limit,
                       @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw ApiProblem.invalid("Use a limit from 1 to 100 and a non-negative offset.");
        }
        return store.orders(status, limit, offset);
    }

    @GetMapping("/orders/{id}")
    Order order(@PathVariable UUID id) {
        return inventory.order(id);
    }

    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Order> place(@Valid @RequestBody OrderInput input) {
        Placement result = inventory.place(input);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.order());
        }
        return ResponseEntity.created(URI.create("/api/orders/" + result.order().id())).body(result.order());
    }

    @PostMapping(value = "/orders/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Order cancel(@PathVariable UUID id) {
        return inventory.cancel(id);
    }

    @GetMapping("/events")
    List<OutboxEvent> events() {
        return store.events();
    }

    @PostMapping(value = "/demo/race", consumes = MediaType.APPLICATION_JSON_VALUE)
    RaceResult runRace(@Valid @RequestBody RaceInput input) {
        return demo.run(input);
    }

    @PostMapping(value = "/demo/race/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    RaceJob startRace(@Valid @RequestBody RaceInput input) {
        return demo.start(input);
    }

    @GetMapping("/demo/race/jobs/{id}")
    RaceProgress raceProgress(@PathVariable String id) {
        return demo.progress(id);
    }
}
