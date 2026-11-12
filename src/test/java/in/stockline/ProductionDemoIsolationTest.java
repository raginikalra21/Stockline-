package in.stockline;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.stockline.domain.model.Models.Role;
import in.stockline.security.JdbcUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=prod",
    "spring.datasource.url=jdbc:h2:mem:stockline-production-demo-isolation;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "stockline.jwt.secret=VGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS0zMi1ieXRlcy1taW5pbXVt"
})
@AutoConfigureMockMvc
class ProductionDemoIsolationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcUserRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void productionServesOnlyTheEmptyDemoAsset() throws Exception {
        mvc.perform(get("/demo-accounts.js")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.startsWith("window.STOCKLINE_DEMO_ACCOUNTS = [];")));
    }

    @Test
    void productionRequiresAuthenticationAndSetsSecureHttpOnlyAuthCookie() throws Exception {
        mvc.perform(get("/api/catalog")).andExpect(status().isUnauthorized());
        users.create("production-cookie-user", passwords.encode("production-cookie-password-123"), Role.ADMIN, List.of());

        var csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        var login = mvc.perform(post("/api/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "production-cookie-user",
                    "password", "production-cookie-password-123"))))
            .andExpect(status().isOk()).andReturn();

        assertThat(login.getResponse().getHeaders("Set-Cookie")).anySatisfy(value ->
            assertThat(value).contains("STOCKLINE_AUTH=").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict"));
    }
}
