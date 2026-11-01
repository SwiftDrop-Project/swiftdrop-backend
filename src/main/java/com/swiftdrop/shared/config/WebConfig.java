package com.swiftdrop.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    /**
     * Puts every SwiftDrop controller under /api/v1. Only our own package is prefixed,
     * so Swagger (/swagger-ui.html) and actuator (/actuator/health) keep their normal paths.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forBasePackage("com.swiftdrop"));
    }
}
