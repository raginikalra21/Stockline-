package in.stockline.api.controller;

import in.stockline.domain.model.Models.Catalog;
import in.stockline.domain.model.Models.Product;
import in.stockline.api.dto.InventoryRequests.ProductInput;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import in.stockline.security.WarehouseAccessService;
import in.stockline.domain.model.Models.UserAccount;

@RestController
@RequestMapping("/api")
public class ProductController {
    private final Store store;
    private final InventoryService inventory;
    private final WarehouseAccessService access;

    public ProductController(Store store, InventoryService inventory, WarehouseAccessService access) {
        this.store = store;
        this.inventory = inventory;
        this.access = access;
    }

    @GetMapping("/catalog")
    public Catalog catalog() {
        Catalog catalog = store.catalog();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserAccount user) || access.isAdmin(user)) return catalog;
        return new Catalog(catalog.products(), catalog.warehouses().stream()
            .filter(warehouse -> access.allowed(user, warehouse.code())).toList());
    }

    @PostMapping(value = "/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Product addProduct(@Valid @RequestBody ProductInput input) { return inventory.addProduct(input); }
}
