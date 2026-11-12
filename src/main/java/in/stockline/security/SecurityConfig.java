package in.stockline.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import in.stockline.domain.model.Models.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import in.stockline.observability.RequestCorrelationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecretKey jwtKey(@Value("${stockline.jwt.secret}") String secret) {
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32) throw new IllegalStateException("JWT secret must decode to at least 32 bytes.");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean JwtDecoder jwtDecoder(SecretKey key) { return NimbusJwtDecoder.withSecretKey(key).build(); }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> databaseUserConverter(JdbcUserRepository users) {
        return jwt -> {
            UserAccount user = users.findById(java.util.UUID.fromString(jwt.getSubject())).orElse(null);
            if (user == null || !user.enabled()) throw new org.springframework.security.authentication.BadCredentialsException("Account is disabled.");
            return new UsernamePasswordAuthenticationToken(user, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        };
    }

    @Bean
    BearerTokenResolver cookieTokenResolver() {
        return request -> {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) return header.substring(7);
            if (request.getCookies() == null) return null;
            for (var cookie : request.getCookies()) if ("STOCKLINE_AUTH".equals(cookie.getName())) return cookie.getValue();
            return null;
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> databaseUserConverter,
            BearerTokenResolver cookieTokenResolver) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http.csrf(config -> config.csrfTokenRepository(csrf).ignoringRequestMatchers(request -> {
                String authorization = request.getHeader("Authorization");
                return authorization != null && authorization.startsWith("Bearer ");
            }))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/password").authenticated()
                .requestMatchers("/", "/index.html", "/styles.css", "/app.js", "/mark.svg", "/api/auth/**",
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(cookieTokenResolver)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(databaseUserConverter)))
            .addFilterBefore(new RequestCorrelationFilter(), BearerTokenAuthenticationFilter.class)
            .httpBasic(config -> config.disable())
            .formLogin(config -> config.disable())
            .logout(config -> config.disable());
        return http.build();
    }
}
