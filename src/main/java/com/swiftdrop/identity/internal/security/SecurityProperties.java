package com.swiftdrop.identity.internal.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from FRONTEND_URL, COOKIE_SECURE, JWT_SECRET, JWT_EXPIRY_MINUTES, JWT_REMEMBER_ME_DAYS,
 * GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.
 */
@Validated
@ConfigurationProperties("swiftdrop.security")
public record SecurityProperties(
        @NotBlank String frontendUrl,
        boolean cookieSecure,
        @Valid @NotNull Jwt jwt,
        Google google) {

    public record Jwt(
            // HS256 needs a key of at least 256 bits.
            @NotBlank(message = "JWT_SECRET is missing. Set it in .env (at least 32 characters).")
            @Size(min = 32, message = "JWT_SECRET must be at least 32 characters long.")
            String secret,
            @Min(1) long expiryMinutes,
            @Min(1) long rememberMeDays) {
    }

    /** "Continue with Google". Both values blank → the feature is off and /oauth2/** returns 404. */
    public record Google(String clientId, String clientSecret) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }

        /** Keeps the client secret out of logs. */
        @Override
        public String toString() {
            return "Google[enabled=" + enabled() + "]";
        }
    }

    public boolean googleEnabled() {
        return google != null && google.enabled();
    }
}
