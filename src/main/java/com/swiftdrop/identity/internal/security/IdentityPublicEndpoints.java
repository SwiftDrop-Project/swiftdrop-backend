package com.swiftdrop.identity.internal.security;

import com.swiftdrop.shared.web.ApiPaths;
import com.swiftdrop.shared.web.PublicEndpoints;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Register, log in, log out and the "Continue with Google" URLs work without logging in. */
@Component
class IdentityPublicEndpoints implements PublicEndpoints {

    @Override
    public Collection<RequestMatcher> matchers() {
        var paths = PublicEndpoints.paths();
        return List.of(
                paths.matcher(HttpMethod.POST, ApiPaths.PREFIX + "/auth/register"),
                paths.matcher(HttpMethod.POST, ApiPaths.PREFIX + "/auth/login"),
                paths.matcher(HttpMethod.POST, ApiPaths.PREFIX + "/auth/logout"),
                // "Continue with Google": start (/oauth2/authorization/google) and Google's redirect back
                // (/login/oauth2/code/google). Both answer 404 when Google sign-in is not configured.
                paths.matcher("/oauth2/**"),
                paths.matcher("/login/oauth2/**"));
    }
}
