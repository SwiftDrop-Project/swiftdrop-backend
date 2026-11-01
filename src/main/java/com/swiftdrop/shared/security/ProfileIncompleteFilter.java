package com.swiftdrop.shared.security;

import com.swiftdrop.shared.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static com.swiftdrop.shared.config.WebConfig.API_PREFIX;

/**
 * A user who signed up with Google but has not finished their profile (token claim
 * {@code status=PROFILE_INCOMPLETE}) may only see who they are and finish the profile.
 * Every other protected endpoint answers 403 PROFILE_INCOMPLETE. Public endpoints are not affected.
 *
 * <p>Runs after the JWT is read and before the role checks, so it applies to every endpoint any module adds.
 */
public class ProfileIncompleteFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    static final RequestMatcher ALLOWED = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, API_PREFIX + "/auth/me"),
            PATHS.matcher(HttpMethod.POST, API_PREFIX + "/auth/complete-profile"));

    private static final String PROFILE_INCOMPLETE = "PROFILE_INCOMPLETE";

    private final JsonSecurityErrorHandlers errorHandlers;

    public ProfileIncompleteFilter(JsonSecurityErrorHandlers errorHandlers) {
        this.errorHandlers = errorHandlers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && PROFILE_INCOMPLETE.equals(token.getToken().getClaimAsString(TokenClaims.STATUS))
                && !ALLOWED.matches(request)) {
            errorHandlers.write(response, ErrorCode.PROFILE_INCOMPLETE);
            return;
        }
        chain.doFilter(request, response);
    }
}
