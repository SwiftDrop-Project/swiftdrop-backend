package com.swiftdrop.merchants.internal.security;

import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.merchants.internal.application.ApiKeyService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes {@link ApiKeyAuthenticationFilter} as a Spring bean so it can be injected into
 * {@code SecurityConfig} without crossing module boundaries:
 *
 * <pre>
 * // In SecurityConfig.securityFilterChain() signature — no merchants.internal import needed:
 * &#64;Qualifier("apiKeyAuthenticationFilter") jakarta.servlet.Filter apiKeyFilter
 *
 * // In the filter chain:
 * .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
 * </pre>
 *
 * The {@link FilterRegistrationBean} prevents Spring Boot from auto-registering the filter
 * as a plain servlet filter outside the security chain (which would cause it to run twice).
 */
@Configuration
class MerchantsSecurityConfig {

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyService apiKeyService,
                                                          MerchantApi merchantApi,
                                                          JsonMapper jsonMapper) {
        return new ApiKeyAuthenticationFilter(apiKeyService, merchantApi, jsonMapper);
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> disableApiKeyFilterAutoRegistration(
            ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }
}
