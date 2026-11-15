package com.swiftdrop.tracking.internal.controller;

import com.swiftdrop.shared.web.ApiPaths;
import com.swiftdrop.shared.web.PublicEndpoints;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Public tracking: the lookup and the public WebSocket handshake. The staff handshake ({@code /api/v1/ws}) is not
 * here on purpose: it must go through the security chain so the SD_TOKEN cookie is checked.
 */
@Component
class TrackingPublicEndpoints implements PublicEndpoints {

    @Override
    public Collection<RequestMatcher> matchers() {
        var paths = PublicEndpoints.paths();
        return List.of(
                paths.matcher(HttpMethod.GET, ApiPaths.PREFIX + "/track/*"),
                paths.matcher(HttpMethod.GET, ApiPaths.PREFIX + "/ws/public"));
    }
}
