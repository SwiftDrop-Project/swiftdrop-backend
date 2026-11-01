package com.swiftdrop.shared.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static com.swiftdrop.shared.config.WebConfig.API_PREFIX;

/** The only endpoints that work without logging in. Everything else is denied by default. */
public final class PublicEndpoints {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    public static final RequestMatcher MATCHER = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, API_PREFIX + "/auth/register"),
            PATHS.matcher(HttpMethod.POST, API_PREFIX + "/auth/login"),
            PATHS.matcher(HttpMethod.POST, API_PREFIX + "/auth/logout"),
            PATHS.matcher(HttpMethod.GET, API_PREFIX + "/meta/districts"),
            PATHS.matcher("/swagger-ui.html"),
            PATHS.matcher("/swagger-ui/**"),
            PATHS.matcher("/v3/api-docs/**"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health/**"),
            // "Continue with Google": start (/oauth2/authorization/google) and Google's redirect back
            // (/login/oauth2/code/google). Both answer 404 when Google sign-in is not configured.
            PATHS.matcher("/oauth2/**"),
            PATHS.matcher("/login/oauth2/**"),
            // Spring forwards here to render errors; it must not be blocked.
            PATHS.matcher("/error"));

    private PublicEndpoints() {
    }
}
