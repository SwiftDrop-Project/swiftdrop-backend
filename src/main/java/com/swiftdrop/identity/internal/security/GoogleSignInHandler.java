package com.swiftdrop.identity.internal.security;

import com.swiftdrop.identity.internal.application.AuthService;
import com.swiftdrop.identity.internal.application.AuthService.AuthResult;
import com.swiftdrop.identity.internal.application.AuthService.GoogleIdentity;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Ends the "Continue with Google" flow by redirecting the browser back to the frontend:
 * <ul>
 *   <li>signed in → SD_TOKEN cookie (remember me) and {@code FRONTEND_URL/auth/callback}</li>
 *   <li>pending, rejected or disabled account → no cookie, {@code FRONTEND_URL/login?error=account_pending_approval} etc.</li>
 *   <li>anything else (cancelled consent, unverified email, Google error) → {@code FRONTEND_URL/login?error=google_failed}</li>
 * </ul>
 * Logs never contain Google tokens, emails or names.
 */
@Component
public class GoogleSignInHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    static final String GOOGLE_FAILED = "google_failed";

    private static final Logger log = LoggerFactory.getLogger(GoogleSignInHandler.class);
    private static final Set<ErrorCode> STATUS_ERRORS = Set.of(
            ErrorCode.ACCOUNT_PENDING_APPROVAL, ErrorCode.ACCOUNT_REJECTED, ErrorCode.ACCOUNT_DISABLED);
    private static final int MAX_NAME_LENGTH = 100;

    private final AuthService authService;
    private final AuthCookies authCookies;
    private final SecurityProperties properties;

    public GoogleSignInHandler(AuthService authService, AuthCookies authCookies, SecurityProperties properties) {
        this.authService = authService;
        this.authCookies = authCookies;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof OidcUser user)) {
            log.warn("Google sign-in rejected: no OIDC user");
            redirectToLogin(response, GOOGLE_FAILED);
            return;
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified()) || isBlank(user.getSubject()) || isBlank(user.getEmail())) {
            log.warn("Google sign-in rejected: email missing or not verified by Google");
            redirectToLogin(response, GOOGLE_FAILED);
            return;
        }
        AuthResult result;
        try {
            result = authService.loginWithGoogle(new GoogleIdentity(user.getSubject(), user.getEmail(), nameOf(user)));
        } catch (ApiException ex) {
            if (STATUS_ERRORS.contains(ex.code())) {
                redirectToLogin(response, ex.code().name().toLowerCase(Locale.ROOT));
            } else {
                log.warn("Google sign-in failed: {}", ex.code());
                redirectToLogin(response, GOOGLE_FAILED);
            }
            return;
        } catch (RuntimeException ex) {
            // Only the type: exception messages can contain the email (e.g. from a database constraint).
            log.warn("Google sign-in failed: {}", ex.getClass().getName());
            redirectToLogin(response, GOOGLE_FAILED);
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.create(result.token(), true).toString());
        response.sendRedirect(properties.frontendUrl() + "/auth/callback");
    }

    /** Cancelled consent, invalid or expired state, failed token exchange, and so on. */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String reason = exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.info("Google sign-in did not complete: {}", reason);
        redirectToLogin(response, GOOGLE_FAILED);
    }

    private void redirectToLogin(HttpServletResponse response, String error) throws IOException {
        response.sendRedirect(properties.frontendUrl() + "/login?error=" + error);
    }

    /** Google's "name" claim, else given + family name, else the part of the email before the @. */
    private static String nameOf(OidcUser user) {
        String name = user.getFullName();
        if (isBlank(name)) {
            name = String.join(" ", nullToEmpty(user.getGivenName()), nullToEmpty(user.getFamilyName()));
        }
        if (isBlank(name)) {
            name = user.getEmail().substring(0, Math.max(user.getEmail().indexOf('@'), 0));
        }
        name = name.trim();
        if (name.isEmpty()) {
            name = "Google user";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH).trim() : name;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
