package com.swiftdrop.identity.internal.security;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds the SD_TOKEN cookie exactly as API.md describes. */
@Component
public class AuthCookies {

    public static final String NAME = "SD_TOKEN";

    private final SecurityProperties properties;

    public AuthCookies(SecurityProperties properties) {
        this.properties = properties;
    }

    /** rememberMe → persistent cookie (Max-Age = JWT_REMEMBER_ME_DAYS); otherwise a session cookie. */
    public ResponseCookie create(String token, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder builder = base(token);
        if (rememberMe) {
            builder.maxAge(Duration.ofDays(properties.jwt().rememberMeDays()));
        }
        return builder.build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .path("/")
                .sameSite("Lax");
    }
}
