package in.stockline.api.controller;

import in.stockline.api.dto.InventoryRequests.OrderInput;
import in.stockline.api.dto.InventoryRequests;
import in.stockline.domain.exception.ApiProblem;
import in.stockline.domain.model.Models.Order;
import in.stockline.domain.model.Models.OrderStatus;
import in.stockline.domain.model.Models.Placement;
import in.stockline.domain.model.Models.AllocationDecision;
import in.stockline.domain.model.AllocationModels.AllocationPreview;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.context.SecurityContextHolder;
import in.stockline.security.WarehouseAccessService;
import in.stockline.domain.model.Models.UserAccount;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final Store store;
    private final InventoryService inventory;
    private final WarehouseAccessService access;

    public OrderController(Store store, InventoryService inventory, WarehouseAccessService access) {
        this.store = store;
        this.inventory = inventory;
        this.access = access;
    }

    @GetMapping
    public List<Order> orders(@RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw ApiProblem.invalid("Use a limit from 1 to 100 and a non-negative offset.");
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount user && !access.isAdmin(user))
            return store.orders(status, limit, offset, java.util.Set.copyOf(user.warehouseCodes()));
        return store.orders(status, limit, offset);
    }

    @GetMapping("/{id}")
    public Order order(@PathVariable UUID id) {
        Order order = inventory.order(id);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserAccount user &&
            order.allocations().stream().anyMatch(a -> !access.allowed(user, a.warehouseCode())))
            throw in.stockline.domain.exception.ApiProblem.forbidden("This order includes a warehouse outside your access scope.");
        return order;
    }

    @GetMapping("/{id}/allocations")
    @Operation(summary = "Read persisted allocation decisions for an order")
    public List<AllocationDecision> allocations(@PathVariable UUID id) {
        order(id); // Applies the existing warehouse-scope check before returning allocation metadata.
        return inventory.allocationDecisions(id);
    }

    @PostMapping(value = "/allocation-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Preview an order allocation without reserving inventory")
    public AllocationPreview allocationPreview(@Valid @RequestBody InventoryRequests.AllocationPreviewInput input) {
        return inventory.previewAllocation(input);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Order> place(@Valid @RequestBody OrderInput input) {
        Placement result = inventory.place(input);
        if (result.replayed()) return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.order());
        return ResponseEntity.created(URI.create("/api/orders/" + result.order().id())).body(result.order());
    }

    @PostMapping(value = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Order cancel(@PathVariable UUID id) { return inventory.cancel(id); }
}
