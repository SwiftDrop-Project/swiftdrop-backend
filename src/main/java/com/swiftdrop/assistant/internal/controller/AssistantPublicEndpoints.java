package com.swiftdrop.assistant.internal.controller;

import com.swiftdrop.shared.web.ApiPaths;
import com.swiftdrop.shared.web.PublicEndpoints;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/** The public chat. {@code /assistant/chat/me} is not here: it needs the login cookie. */
@Component
class AssistantPublicEndpoints implements PublicEndpoints {

    @Override
    public Collection<RequestMatcher> matchers() {
        var paths = PublicEndpoints.paths();
        return List.of(paths.matcher(HttpMethod.POST, ApiPaths.PREFIX + "/assistant/chat"));
    }
}
