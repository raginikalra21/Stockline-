package in.stockline.api.controller;

import in.stockline.api.dto.InventoryRequests.WarehouseInput;
import in.stockline.api.dto.InventoryRequests.WarehouseConfigurationInput;
import in.stockline.domain.model.Models.Warehouse;
import in.stockline.service.inventory.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
public class WarehouseController {
    private final InventoryService inventory;

    public WarehouseController(InventoryService inventory) { this.inventory = inventory; }

    @PostMapping(value = "/warehouses", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Warehouse addWarehouse(@Valid @RequestBody WarehouseInput input) { return inventory.addWarehouse(input); }

    @GetMapping("/warehouses/{code}/configuration")
    @Operation(summary = "Read warehouse allocation configuration")
    public Warehouse configuration(@PathVariable String code) { return inventory.warehouseConfiguration(code); }

    @PutMapping(value = "/warehouses/{code}/configuration", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update warehouse allocation configuration")
    public Warehouse updateConfiguration(@PathVariable String code,
            @Valid @RequestBody WarehouseConfigurationInput input) {
        return inventory.updateWarehouseConfiguration(code, input);
    }
}
