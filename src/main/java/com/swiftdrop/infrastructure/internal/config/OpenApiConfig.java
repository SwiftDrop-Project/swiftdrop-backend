package com.swiftdrop.infrastructure.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html. The SD_TOKEN cookie scheme is added by the identity module. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI swiftdropOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SwiftDrop API")
                        .version("v1")
                        .description("""
                                Contract: API.md. Log in with POST /api/v1/auth/login. The browser then keeps the \
                                httpOnly SD_TOKEN cookie and sends it with every call from this page."""));
    }
}
