package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.OneTimeCode;
import com.swiftdrop.identity.internal.dto.SignUpFields.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /auth/password/reset. {@code identifier} must be the one used for /auth/password/forgot's account. */
public record ResetPasswordRequest(
        @Schema(example = "0771234567", description = "Email or mobile number of the account")
        @NotBlank(message = SignUpFields.IDENTIFIER_MESSAGE)
        @Size(max = 254, message = SignUpFields.IDENTIFIER_MESSAGE)
        String identifier,
        @Schema(example = "482913", description = "The 6-digit code from the email") @OneTimeCode String code,
        @Schema(example = "newSecret456") @Password String newPassword) {

    /** Keeps the identifier, code and password out of logs. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[]";
    }
}
