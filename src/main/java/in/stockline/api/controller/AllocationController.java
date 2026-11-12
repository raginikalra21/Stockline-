package in.stockline.api.controller;

import in.stockline.api.dto.InventoryRequests.AllocationStrategyInput;
import in.stockline.domain.model.AllocationModels.AllocationStrategyConfiguration;
import in.stockline.domain.model.Models.AllocationStrategy;
import in.stockline.service.inventory.InventoryService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/allocation")
public class AllocationController {
    private final InventoryService inventory;
    public AllocationController(InventoryService inventory) { this.inventory = inventory; }

    @GetMapping("/strategies")
    @Operation(summary = "List supported allocation strategies and the network default")
    public AllocationStrategyConfiguration strategies() { return inventory.allocationStrategyConfiguration(); }

    @PutMapping("/strategy")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set the network default allocation strategy")
    public AllocationStrategy setDefault(@Valid @RequestBody AllocationStrategyInput input) {
        return inventory.setDefaultAllocationStrategy(input.strategy());
    }
}
