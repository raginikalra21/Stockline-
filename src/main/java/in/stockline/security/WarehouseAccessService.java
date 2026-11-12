package in.stockline.security;

import in.stockline.domain.exception.ApiProblem;
import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import org.springframework.stereotype.Service;

@Service
public class WarehouseAccessService {
    private final JdbcUserRepository users;
    public WarehouseAccessService(JdbcUserRepository users) { this.users = users; }
    public void require(UserAccount user, String warehouse) {
        if (user.role() != Role.ADMIN && !users.warehouseAllowed(user.id(), warehouse))
            throw ApiProblem.forbidden("You do not have access to warehouse " + warehouse + ".");
    }
    public boolean allowed(UserAccount user, String warehouse) {
        return user.role() == Role.ADMIN || users.warehouseAllowed(user.id(), warehouse);
    }
    public boolean isAdmin(UserAccount user) { return user.role() == Role.ADMIN; }
}
