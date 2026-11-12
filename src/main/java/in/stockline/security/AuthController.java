package in.stockline.security;

import in.stockline.domain.model.Models.UserAccount;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasswordChangeRequest(@NotBlank String currentPassword,
                                        @NotBlank @jakarta.validation.constraints.Size(min = 12, max = 128) String newPassword) {}
    public record Profile(String username, String role, java.util.List<String> warehouseCodes) {}
    private final JdbcUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final boolean secure;

    public AuthController(JdbcUserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
            @Value("${stockline.auth.secure-cookie:false}") boolean secure) {
        this.users = users; this.passwords = passwords; this.encoder = encoder; this.secure = secure;
    }

    @GetMapping("/csrf") public Map<String, String> csrf(jakarta.servlet.http.HttpServletRequest request) {
        var token = (org.springframework.security.web.csrf.CsrfToken) request.getAttribute("_csrf");
        return Map.of("token", token.getToken());
    }

    @PostMapping("/login")
    public Profile login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        UserAccount user = users.findByUsername(request.username()).filter(UserAccount::enabled)
            .filter(account -> passwords.matches(request.password(), account.passwordHash()))
            .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Invalid username or password."));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("stockline").issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(20)))
            .subject(user.id().toString()).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        ResponseCookie cookie = ResponseCookie.from("STOCKLINE_AUTH", token).httpOnly(true).secure(secure)
            .sameSite("Strict").path("/").maxAge(Duration.ofMinutes(20)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return profile(user);
    }

    @GetMapping("/me") public Profile me(Authentication authentication) {
        return profile((UserAccount) authentication.getPrincipal());
    }

    @PostMapping("/password") public Map<String, Boolean> changePassword(
            @Valid @RequestBody PasswordChangeRequest request, Authentication authentication) {
        UserAccount user = (UserAccount) authentication.getPrincipal();
        if (!passwords.matches(request.currentPassword(), user.passwordHash()))
            throw new org.springframework.security.authentication.BadCredentialsException("Current password is incorrect.");
        users.changePassword(user.id(), passwords.encode(request.newPassword()));
        return Map.of("changed", true);
    }

    @PostMapping("/logout") public Map<String, Boolean> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("STOCKLINE_AUTH", "").httpOnly(true)
            .secure(secure).sameSite("Strict").path("/").maxAge(Duration.ZERO).build().toString());
        return Map.of("loggedOut", true);
    }

    private Profile profile(UserAccount user) { return new Profile(user.username(), user.role().name(), user.warehouseCodes()); }
}
