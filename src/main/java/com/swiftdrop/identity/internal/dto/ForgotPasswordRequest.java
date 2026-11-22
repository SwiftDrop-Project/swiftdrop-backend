package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.OptionalEmailAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /auth/password/forgot. {@code identifier} is an email or a Sri Lankan mobile number.
 * Optional {@code confirmationEmail} requires the resolved account's stored email to match before issuing a code.
 */
public record ForgotPasswordRequest(
        @Schema(example = "0771234567", description = "Email or mobile number, as for login")
        @NotBlank(message = SignUpFields.IDENTIFIER_MESSAGE)
        @Size(max = 254, message = SignUpFields.IDENTIFIER_MESSAGE)
        String identifier,
        @Schema(example = "ayesha@example.com",
                description = "Optional registered email to confirm against the account looked up by identifier")
        @OptionalEmailAddress
        String confirmationEmail) {

    public ForgotPasswordRequest(String identifier) {
        this(identifier, null);
    }

    /** Keeps the identifier and confirmation email out of logs. */
    @Override
    public String toString() {
        return "ForgotPasswordRequest[]";
    }
}
