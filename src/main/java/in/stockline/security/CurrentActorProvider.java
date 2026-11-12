package in.stockline.security;

import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentActorProvider {
    public record Actor(UUID userId, String username, String role) {}
    public Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserAccount user)
            return new Actor(user.id(), user.username(), user.role().name());
        return new Actor(null, "SYSTEM", null);
    }
}
