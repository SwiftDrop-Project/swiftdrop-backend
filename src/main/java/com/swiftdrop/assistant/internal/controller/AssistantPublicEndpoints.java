package com.swiftdrop.assistant.internal.controller;

import com.swiftdrop.shared.web.ApiPaths;
import com.swiftdrop.shared.web.PublicEndpoints;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * The public chat and the MCP endpoint. {@code /assistant/chat/me} is not here: it needs the login cookie. MCP is
 * public for every method, so a client's GET gets 405 (no event stream) rather than a 401 that would start an OAuth
 * flow in MCP clients.
 */
@Component
class AssistantPublicEndpoints implements PublicEndpoints {

    @Override
    public Collection<RequestMatcher> matchers() {
        var paths = PublicEndpoints.paths();
        return List.of(
                paths.matcher(HttpMethod.POST, ApiPaths.PREFIX + "/assistant/chat"),
                paths.matcher(ApiPaths.PREFIX + "/mcp"));
    }
}
