package in.stockline.api.controller;

import in.stockline.domain.model.Models.Overview;
import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import in.stockline.persistence.repository.Store;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

@RestController
@RequestMapping("/api")
public class AnalyticsController {
    private final Store store;

    public AnalyticsController(Store store) { this.store = store; }

    @GetMapping("/overview")
    public Overview overview() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user
                && user.role() != Role.ADMIN) {
            return store.overview(Set.copyOf(user.warehouseCodes()));
        }
        return store.overview();
    }
}
