package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.EmailAddress;
import com.swiftdrop.identity.internal.dto.SignUpFields.OneTimeCode;
import io.swagger.v3.oas.annotations.media.Schema;

/** Body of POST /auth/email-verification/verify. */
public record VerifyEmailRequest(
        @Schema(example = "ayesha@example.com") @EmailAddress String email,
        @Schema(example = "482913", description = "The 6-digit code from the email") @OneTimeCode String code) {

    /** Keeps the email and the code out of logs. */
    @Override
    public String toString() {
        return "VerifyEmailRequest[]";
    }
}
