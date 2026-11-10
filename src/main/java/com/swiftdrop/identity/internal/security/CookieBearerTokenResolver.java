package com.swiftdrop.identity.internal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.UUID;

/**
 * Reads the JWT from the httpOnly SD_TOKEN cookie instead of the Authorization header.
 *
 * <p>Public endpoints get no token on purpose: an expired or broken cookie would otherwise make the
 * resource server reject the request with 401, so a user with an old cookie could never log in again.
 *
 * <p>Requests already authenticated via an explicit {@code X-Api-Key} header also get no cookie token so
 * valid API-key authentication takes precedence over any valid or expired ambient {@code SD_TOKEN} cookie.
 * Invalid or revoked API keys are already rejected with 401/403 before reaching this resolver.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final RequestMatcher publicEndpoints;

    /** {@code publicEndpoints}: every module's public URLs combined (see SecurityConfig). */
    public CookieBearerTokenResolver(RequestMatcher publicEndpoints) {
        this.publicEndpoints = publicEndpoints;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (publicEndpoints.matches(request)) {
            return null;
        }
        if (isAuthenticatedByApiKey(request)) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookies.NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean isAuthenticatedByApiKey(HttpServletRequest request) {
        return request.getHeader(API_KEY_HEADER) != null
                && SecurityContextHolder.getContext().getAuthentication() instanceof UsernamePasswordAuthenticationToken auth
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof UUID;
    }
}
