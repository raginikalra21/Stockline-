package in.stockline.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean OpenAPI stocklineOpenApi() {
        String scheme = "stocklineBearer";
        return new OpenAPI().info(new Info().title("Stockline Inventory API")
            .version("1.0.0").description("Multi-warehouse inventory, allocation, order, and audit API."))
            .components(new Components().addSecuritySchemes(scheme,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
