package com.swiftdrop.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of POST /auth/login. {@code identifier} is an email or a Sri Lankan mobile number. */
public record LoginRequest(
        @Schema(example = "customer@swiftdrop.lk", description = "Email or mobile number (0771234567, 771234567, +94771234567)")
        @NotBlank(message = "Enter your mobile number or email.")
        String identifier,

        @Schema(example = "Demo12345")
        @NotBlank(message = "Enter your password.")
        String password,

        @Schema(example = "true", description = "true: stay logged in for JWT_REMEMBER_ME_DAYS; false: until the browser closes")
        Boolean rememberMe) {

    public boolean rememberMeOrDefault() {
        return Boolean.TRUE.equals(rememberMe);
    }

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "LoginRequest[rememberMe=" + rememberMe + "]";
    }
}
