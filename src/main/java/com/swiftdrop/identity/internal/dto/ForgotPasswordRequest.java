package com.swiftdrop.identity.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /auth/password/forgot. {@code identifier} is an email or a Sri Lankan mobile number. */
public record ForgotPasswordRequest(
        @Schema(example = "0771234567", description = "Email or mobile number, as for login")
        @NotBlank(message = SignUpFields.IDENTIFIER_MESSAGE)
        @Size(max = 254, message = SignUpFields.IDENTIFIER_MESSAGE)
        String identifier) {

    /** Keeps the identifier out of logs. */
    @Override
    public String toString() {
        return "ForgotPasswordRequest[]";
    }
}
