package com.swiftdrop.shared.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Reads the JWT from the httpOnly SD_TOKEN cookie instead of the Authorization header.
 *
 * <p>Public endpoints get no token on purpose: an expired or broken cookie would otherwise make the
 * resource server reject the request with 401, so a user with an old cookie could never log in again.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        if (PublicEndpoints.MATCHER.matches(request)) {
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
}
