package in.stockline.api.controller;

import in.stockline.api.dto.InventoryRequests.*;
import in.stockline.domain.model.Models.ProductInventory;
import in.stockline.domain.model.Models.Stock;
import in.stockline.domain.model.Models.NetworkInventorySummary;
import in.stockline.domain.model.Models.WarehouseInventorySummary;
import in.stockline.domain.model.Models.LowStockAlert;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import in.stockline.security.WarehouseAccessService;
import in.stockline.domain.model.Models.UserAccount;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final Store store;
    private final InventoryService inventory;
    private final WarehouseAccessService access;

    public InventoryController(Store store, InventoryService inventory, WarehouseAccessService access) {
        this.store = store;
        this.inventory = inventory;
        this.access = access;
    }

    @GetMapping
    public List<Stock> stock() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount user && !access.isAdmin(user))
            return store.inventory().stream().filter(s -> access.allowed(user, s.warehouseCode())).toList();
        return store.inventory();
    }

    @GetMapping("/products")
    public List<ProductInventory> productInventory() { return store.productInventory(visibleWarehouses()); }

    @GetMapping("/network-summary")
    public NetworkInventorySummary networkSummary() { return store.networkInventorySummary(visibleWarehouses()); }

    @GetMapping("/products/{sku}/distribution")
    public ProductInventory productDistribution(@org.springframework.web.bind.annotation.PathVariable String sku) {
        return store.productInventoryForSku(sku, visibleWarehouses()).stream().findFirst()
            .orElseThrow(() -> in.stockline.domain.exception.ApiProblem.notFound("That product does not exist."));
    }

    @GetMapping("/warehouses/{warehouseCode}/summary")
    public WarehouseInventorySummary warehouseSummary(
            @org.springframework.web.bind.annotation.PathVariable String warehouseCode) {
        requireWarehouse(warehouseCode);
        return store.warehouseInventorySummary(warehouseCode);
    }

    @GetMapping("/low-stock")
    public List<LowStockAlert> lowStockProducts() { return store.lowStockAlerts(visibleWarehouses()); }

    @PostMapping(value = "/restock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Stock restock(@Valid @RequestBody RestockInput input) { return inventory.restock(input); }

    @PostMapping(value = "/transfer", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER')")
    public List<Stock> transfer(@Valid @RequestBody TransferInput input) { return inventory.transfer(input); }

    @PostMapping(value = "/adjustments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER')")
    public Stock adjust(@Valid @RequestBody StockAdjustmentInput input) { return inventory.adjust(input); }

    @PostMapping(value = "/damaged", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Stock damage(@Valid @RequestBody DamagedStockInput input) { return inventory.recordDamage(input); }

    @PostMapping(value = "/write-offs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER')")
    public Stock writeOff(@Valid @RequestBody WriteOffInput input) { return inventory.writeOff(input); }

    @PostMapping(value = "/returns", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Stock returnStock(@Valid @RequestBody ReturnInput input) { return inventory.returnStock(input); }

    @PostMapping(value = "/threshold", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER')")
    public Stock setThreshold(@Valid @RequestBody ThresholdInput input) { return inventory.setThreshold(input); }

    private Set<String> visibleWarehouses() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount user && !access.isAdmin(user))
            return Set.copyOf(user.warehouseCodes());
        return null;
    }

    private void requireWarehouse(String warehouseCode) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount user) access.require(user, warehouseCode);
    }
}
