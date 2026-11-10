package com.swiftdrop.identity.internal.security;

import com.swiftdrop.identity.internal.security.SessionStateCache.SessionState;
import com.swiftdrop.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

import static com.swiftdrop.shared.web.ApiPaths.PREFIX;

/**
 * Staff accounts provisioned with an initial password or reset by an admin ({@code mustChangePassword=true}) may only
 * inspect their own account, change their password, or log out until they set a personal password.
 * Every other protected endpoint answers {@code 403 PASSWORD_CHANGE_REQUIRED}.
 *
 * <p>Non-JWT principals (such as merchant API-key requests) and public endpoints are not affected.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    static final RequestMatcher ALLOWED = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, PREFIX + "/auth/me"),
            PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/password/change"),
            PATHS.matcher(HttpMethod.POST, PREFIX + "/auth/logout"));

    private final SessionStateCache sessions;
    private final JsonSecurityErrorHandlers errorHandlers;

    public PasswordChangeRequiredFilter(SessionStateCache sessions, JsonSecurityErrorHandlers errorHandlers) {
        this.sessions = sessions;
        this.errorHandlers = errorHandlers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && requiresPasswordChange(token.getToken())
                && !ALLOWED.matches(request)) {
            errorHandlers.write(response, ErrorCode.PASSWORD_CHANGE_REQUIRED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresPasswordChange(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            return sessions.get(userId)
                    .map(SessionState::mustChangePassword)
                    .orElseGet(() -> Boolean.TRUE.equals(jwt.getClaimAsBoolean(TokenClaims.MUST_CHANGE_PASSWORD)));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Boolean.TRUE.equals(jwt.getClaimAsBoolean(TokenClaims.MUST_CHANGE_PASSWORD));
        }
    }
}

