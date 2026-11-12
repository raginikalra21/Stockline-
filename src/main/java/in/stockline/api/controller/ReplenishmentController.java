package in.stockline.api.controller;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.domain.model.Models.ReplenishmentSuggestion;
import in.stockline.domain.model.Models.UserAccount;
import in.stockline.persistence.repository.Store;
import in.stockline.security.WarehouseAccessService;
import in.stockline.service.inventory.InventoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory/replenishment-suggestions")
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER')")
public class ReplenishmentController {
    private final Store store;
    private final WarehouseAccessService access;
    public ReplenishmentController(Store store, WarehouseAccessService access) { this.store = store; this.access = access; }

    @GetMapping public List<ReplenishmentSuggestion> suggestions() {
        UserAccount user = (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<ReplenishmentSuggestion> rows = store.replenishmentSuggestions(null);
        return user.role() == in.stockline.domain.model.Models.Role.ADMIN ? rows
            : rows.stream().filter(row -> access.allowed(user, row.warehouseCode())).toList();
    }

    @PostMapping("/{id}/dismiss") public ReplenishmentSuggestion dismiss(@PathVariable UUID id) {
        UserAccount user = (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        ReplenishmentSuggestion row = store.replenishmentSuggestions(null).stream()
            .filter(suggestion -> suggestion.suggestionId().equals(id)).findFirst()
            .orElseThrow(() -> ApiProblem.notFound("That active replenishment suggestion does not exist."));
        access.require(user, row.warehouseCode());
        if (!store.dismissSuggestion(id, row.warehouseCode(), InventoryService.now()))
            throw ApiProblem.conflict("SUGGESTION_CHANGED", "The suggestion is no longer active.");
        return store.suggestion(id).orElse(row);
    }
}
