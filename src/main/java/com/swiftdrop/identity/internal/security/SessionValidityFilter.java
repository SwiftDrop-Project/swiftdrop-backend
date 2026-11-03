package com.swiftdrop.identity.internal.security;

import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.security.SessionStateCache.SessionState;
import com.swiftdrop.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ends sessions on the server side. A valid JWT is only accepted while, in the database (cached up to 30 s, see
 * {@link SessionStateCache}):
 * <ul>
 *   <li>the user still exists,</li>
 *   <li>the token's "ver" claim equals the user's token version (a password change or reset increments it), and</li>
 *   <li>the status is ACTIVE or PROFILE_INCOMPLETE (so disabled, rejected and unverified users are logged out).</li>
 * </ul>
 * Otherwise: 401 UNAUTHENTICATED and the SD_TOKEN cookie is cleared. Runs right after the JWT is read, before the
 * PROFILE_INCOMPLETE restriction and the role checks. Public endpoints carry no token and are not affected.
 */
public class SessionValidityFilter extends OncePerRequestFilter {

    private static final Set<UserStatus> SESSION_STATUSES = Set.of(UserStatus.ACTIVE, UserStatus.PROFILE_INCOMPLETE);

    private final SessionStateCache sessions;
    private final JsonSecurityErrorHandlers errorHandlers;
    private final AuthCookies authCookies;

    public SessionValidityFilter(SessionStateCache sessions, JsonSecurityErrorHandlers errorHandlers,
                                 AuthCookies authCookies) {
        this.sessions = sessions;
        this.errorHandlers = errorHandlers;
        this.authCookies = authCookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && !isCurrent(token.getToken())) {
            SecurityContextHolder.clearContext();
            response.addHeader(HttpHeaders.SET_COOKIE, authCookies.clear().toString());
            errorHandlers.write(response, ErrorCode.UNAUTHENTICATED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isCurrent(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
        Object version = jwt.getClaim(TokenClaims.VERSION);
        if (!(version instanceof Number tokenVersion)) {
            return false;
        }
        Optional<SessionState> state = sessions.get(userId);
        return state.isPresent()
                && state.get().tokenVersion() == tokenVersion.intValue()
                && SESSION_STATUSES.contains(state.get().status());
    }
}
