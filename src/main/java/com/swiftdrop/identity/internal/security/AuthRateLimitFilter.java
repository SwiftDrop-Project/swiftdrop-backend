package com.swiftdrop.identity.internal.security;

import com.swiftdrop.identity.internal.security.AuthRateLimiter.Action;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

import static com.swiftdrop.shared.web.ApiPaths.PREFIX;

/**
 * Per-IP rate limit on register, login, email verification and the password endpoints: over the limit →
 * 429 TOO_MANY_REQUESTS with {@code Retry-After} (seconds). Runs before the token is read, so a flood of requests
 * costs no database work. The client IP is the TCP peer address; there is no trusted proxy in front of the backend,
 * so {@code X-Forwarded-For} is ignored (a client could fake it).
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    private final Map<RequestMatcher, Action> actions = new LinkedHashMap<>();
    private final AuthRateLimiter limiter;
    private final JsonSecurityErrorHandlers errorHandlers;

    public AuthRateLimitFilter(AuthRateLimiter limiter, JsonSecurityErrorHandlers errorHandlers) {
        this.limiter = limiter;
        this.errorHandlers = errorHandlers;
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/login"), Action.LOGIN);
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/register"), Action.REGISTER);
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/email-verification/verify"), Action.VERIFY);
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/email-verification/resend"), Action.RESEND);
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/password/forgot"), Action.FORGOT);
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/password/reset"), Action.RESET);
        actions.put(PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/password/change"), Action.CHANGE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Map.Entry<RequestMatcher, Action> entry : actions.entrySet()) {
            if (entry.getKey().matches(request)) {
                OptionalLong retryAfter = limiter.tryAcquire(entry.getValue(), request.getRemoteAddr());
                if (retryAfter.isPresent()) {
                    errorHandlers.writeTooManyRequests(response, retryAfter.getAsLong());
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }
}
