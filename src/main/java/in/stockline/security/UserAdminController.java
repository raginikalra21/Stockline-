package in.stockline.security;

import in.stockline.domain.model.Models.Role;
import in.stockline.domain.model.Models.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    public record CreateUser(@NotBlank @Size(max=64) String username, @NotBlank @Size(min=12,max=128) String password,
                             @NotNull Role role, List<String> warehouseCodes) {}
    public record UpdateUser(@NotNull Role role, boolean enabled, List<String> warehouseCodes) {}
    public record UserView(UUID id, String username, Role role, boolean enabled, java.time.OffsetDateTime createdAt,
                           List<String> warehouseCodes) {
        static UserView from(UserAccount user) { return new UserView(user.id(), user.username(), user.role(), user.enabled(), user.createdAt(), user.warehouseCodes()); }
    }
    private final JdbcUserRepository users; private final PasswordEncoder passwords;
    public UserAdminController(JdbcUserRepository users, PasswordEncoder passwords) { this.users=users; this.passwords=passwords; }
    @GetMapping public List<UserView> list() { return users.list().stream().map(UserView::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUser input) {
        return UserView.from(users.create(input.username(), passwords.encode(input.password()), input.role(), safe(input.warehouseCodes())));
    }
    @PutMapping("/{id}") public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUser input) {
        return UserView.from(users.update(id, input.role(), input.enabled(), safe(input.warehouseCodes())));
    }
    private List<String> safe(List<String> codes) { return codes == null ? List.of() : List.copyOf(codes); }
}
