package com.swiftdrop.shared.security;

import com.swiftdrop.auth.api.GoogleSignInHandler;
import com.swiftdrop.shared.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static com.swiftdrop.shared.config.WebConfig.API_PREFIX;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonSecurityErrorHandlers errorHandlers,
                                            SecurityProperties properties, GoogleSignInHandler googleSignInHandler,
                                            JsonMapper jsonMapper) throws Exception {
        http
                // CSRF protection is disabled because it is not needed for this API:
                // - the SD_TOKEN cookie is SameSite=Lax, so browsers don't send it on cross-site POST/PUT/DELETE;
                // - CORS only allows FRONTEND_URL to make credentialed requests and read responses;
                // - write endpoints only accept application/json, which a plain HTML form cannot send
                //   without a CORS preflight.
                // The Google flow has its own CSRF protection: the OAuth2 "state" parameter.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicEndpoints.MATCHER).permitAll()
                        .requestMatchers(API_PREFIX + "/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(new CookieBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errorHandlers.unauthenticated())
                        .accessDeniedHandler(errorHandlers.forbidden()))
                .addFilterAfter(new ProfileIncompleteFilter(errorHandlers), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers.unauthenticated())
                        .accessDeniedHandler(errorHandlers.forbidden()));

        // Without credentials no OAuth2 filter is registered, so /oauth2/** falls through to a normal 404.
        if (properties.googleEnabled()) {
            http.oauth2Login(oauth2 -> oauth2
                    .clientRegistrationRepository(new InMemoryClientRegistrationRepository(google(properties)))
                    .authorizationEndpoint(endpoint -> endpoint
                            .authorizationRequestRepository(new CookieAuthorizationRequestRepository(jsonMapper, properties)))
                    // Google's tokens are only needed once, to read the user; never keep them.
                    .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                    .securityContextRepository(new NullSecurityContextRepository())
                    // A custom login page turns off Spring's generated HTML login page at /login.
                    .loginPage(properties.frontendUrl() + "/login")
                    .successHandler(googleSignInHandler)
                    .failureHandler(googleSignInHandler));
        }
        return http.build();
    }

    /** Scopes openid, profile, email; redirect URI {baseUrl}/login/oauth2/code/google. */
    private static ClientRegistration google(SecurityProperties properties) {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(properties.google().clientId())
                .clientSecret(properties.google().clientSecret())
                .build();
    }

    /** Maps the JWT {@code role} claim (e.g. ADMIN) to the authority ROLE_ADMIN. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenClaims.ROLE);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Only the frontend may call the API from a browser, with cookies. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(properties.frontendUrl()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(API_PREFIX + "/**", config);
        return source;
    }

    /** Stores nothing: the default would keep every user's Google access token in memory. */
    private static final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                         Authentication principal,
                                                                         HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                         HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                           HttpServletRequest request, HttpServletResponse response) {
        }
    }
}
