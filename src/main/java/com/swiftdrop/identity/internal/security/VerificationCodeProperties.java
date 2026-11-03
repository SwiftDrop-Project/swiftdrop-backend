package com.swiftdrop.identity.internal.security;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from VERIFICATION_CODE_SECRET: the HMAC key for stored email codes. Optional; when blank, a key derived
 * from JWT_SECRET is used (see {@link CodeHasher}).
 */
@Validated
@ConfigurationProperties("swiftdrop.security.verification-codes")
public record VerificationCodeProperties(String secret) {

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    @AssertTrue(message = "VERIFICATION_CODE_SECRET must be at least 32 characters long (or leave it empty).")
    public boolean isSecretLongEnough() {
        return !hasSecret() || secret.length() >= 32;
    }

    /** Keeps the secret out of logs. */
    @Override
    public String toString() {
        return "VerificationCodeProperties[secret=" + (hasSecret() ? "set" : "derived") + "]";
    }
}
