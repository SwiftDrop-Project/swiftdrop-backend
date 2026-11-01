package com.swiftdrop.shared.config;

import com.swiftdrop.shared.security.AuthCookies;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                                httpOnly SD_TOKEN cookie and sends it with every call from this page."""))
                .components(new Components().addSecuritySchemes(AuthCookies.NAME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name(AuthCookies.NAME)))
                .addSecurityItem(new SecurityRequirement().addList(AuthCookies.NAME));
    }
}
