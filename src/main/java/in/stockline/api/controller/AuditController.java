package in.stockline.api.controller;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.domain.model.Models.InventoryMovement;
import in.stockline.domain.model.Models.LowStockAlert;
import in.stockline.domain.model.Models.MovementType;
import in.stockline.domain.model.Models.ReconciliationLine;
import in.stockline.persistence.repository.Store;
import in.stockline.service.audit.InventoryAuditService;
import in.stockline.observability.StocklineMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import in.stockline.security.WarehouseAccessService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Set;

@RestController
@RequestMapping("/api/inventory")
public class AuditController {
    private final InventoryAuditService audit;
    private final StocklineMetrics metrics;
    private final WarehouseAccessService access;

    public AuditController(InventoryAuditService audit, StocklineMetrics metrics, WarehouseAccessService access) {
        this.audit = audit; this.metrics = metrics; this.access = access;
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<LowStockAlert> alerts() { return audit.alerts(); }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ReconciliationLine> reconciliation() {
        List<ReconciliationLine> result = audit.reconcile();
        metrics.reconciliation(result.stream().allMatch(line -> "VALID".equals(line.overallIntegrityStatus())));
        return result;
    }

    @GetMapping("/movements")
    public List<InventoryMovement> movements(@RequestParam(required = false) String search,
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
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> warehouseScope = null;
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user && user.role() != Role.ADMIN) {
            if (warehouseCode != null) access.require(user, warehouseCode);
            warehouseScope = Set.copyOf(user.warehouseCodes());
        }
        return audit.movements(search, warehouseCode, type, from, to, orderId, limit, offset, warehouseScope);
    }
}
