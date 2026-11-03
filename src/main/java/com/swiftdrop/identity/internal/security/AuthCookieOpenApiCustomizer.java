package com.swiftdrop.identity.internal.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/** Tells Swagger UI that the API is secured by the SD_TOKEN cookie (the base OpenAPI info is in infrastructure). */
@Component
class AuthCookieOpenApiCustomizer implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSecuritySchemes(AuthCookies.NAME, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(AuthCookies.NAME));
        if (openApi.getSecurity() == null || openApi.getSecurity().stream()
                .noneMatch(requirement -> requirement.containsKey(AuthCookies.NAME))) {
            openApi.addSecurityItem(new SecurityRequirement().addList(AuthCookies.NAME));
        }
    }
}
