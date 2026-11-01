package com.swiftdrop.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bound from FRONTEND_URL, COOKIE_SECURE, JWT_SECRET, JWT_EXPIRY_MINUTES and JWT_REMEMBER_ME_DAYS. */
@Validated
@ConfigurationProperties("swiftdrop.security")
public record SecurityProperties(
        @NotBlank String frontendUrl,
        boolean cookieSecure,
        @Valid @NotNull Jwt jwt) {

    public record Jwt(
            // HS256 needs a key of at least 256 bits.
            @NotBlank(message = "JWT_SECRET is missing. Set it in .env (at least 32 characters).")
            @Size(min = 32, message = "JWT_SECRET must be at least 32 characters long.")
            String secret,
            @Min(1) long expiryMinutes,
            @Min(1) long rememberMeDays) {
    }
}
