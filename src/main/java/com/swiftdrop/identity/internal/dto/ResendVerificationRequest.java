package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.EmailAddress;
import io.swagger.v3.oas.annotations.media.Schema;

/** Body of POST /auth/email-verification/resend. */
public record ResendVerificationRequest(@Schema(example = "ayesha@example.com") @EmailAddress String email) {

    /** Keeps the email out of logs. */
    @Override
    public String toString() {
        return "ResendVerificationRequest[]";
    }
}
