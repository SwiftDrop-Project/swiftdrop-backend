package com.swiftdrop.shared.web;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Collection;

/**
 * Implemented (as a Spring bean) by every module that has endpoints which work without logging in.
 * The security filter chain collects all of these beans, so a module opens its own public URLs without
 * editing the security configuration. Everything else is denied by default. Only open what API.md marks public.
 *
 * <p>Public endpoints also ignore the SD_TOKEN cookie, so an expired cookie never blocks them.
 */
public interface PublicEndpoints {

    /** The URL patterns (with HTTP method where it matters) that are public. Paths include {@link ApiPaths#PREFIX}. */
    Collection<RequestMatcher> matchers();

    /** Builder for path-pattern matchers, e.g. {@code paths().matcher(HttpMethod.POST, ApiPaths.PREFIX + "/auth/login")}. */
    static PathPatternRequestMatcher.Builder paths() {
        return PathPatternRequestMatcher.withDefaults();
    }
}
