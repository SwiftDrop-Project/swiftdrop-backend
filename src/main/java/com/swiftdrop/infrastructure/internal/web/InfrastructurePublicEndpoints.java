package com.swiftdrop.infrastructure.internal.web;

import com.swiftdrop.shared.web.ApiPaths;
import com.swiftdrop.shared.web.PublicEndpoints;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Districts, Swagger, the health check and Spring's error page work without logging in. */
@Component
class InfrastructurePublicEndpoints implements PublicEndpoints {

    @Override
    public Collection<RequestMatcher> matchers() {
        var paths = PublicEndpoints.paths();
        return List.of(
                paths.matcher(HttpMethod.GET, ApiPaths.PREFIX + "/meta/districts"),
                paths.matcher("/swagger-ui.html"),
                paths.matcher("/swagger-ui/**"),
                paths.matcher("/v3/api-docs/**"),
                paths.matcher(HttpMethod.GET, "/actuator/health"),
                paths.matcher(HttpMethod.GET, "/actuator/health/**"),
                // Spring forwards here to render errors; it must not be blocked.
                paths.matcher("/error"));
    }
}
