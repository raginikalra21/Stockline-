package in.stockline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void productionServesOnlyTheEmptyDemoAsset() throws Exception {
        mvc.perform(get("/demo-accounts.js")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.startsWith("window.STOCKLINE_DEMO_ACCOUNTS = [];")));
    }
}
