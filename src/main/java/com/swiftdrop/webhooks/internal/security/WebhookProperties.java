package com.swiftdrop.webhooks.internal.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from WEBHOOK_ENCRYPTION_KEY (empty: the JWT secret, see application.yml) and
 * SWIFTDROP_WEBHOOKS_ALLOW_INSECURE_URLS.
 *
 * @param encryptionKey     key material for {@link SecretCipher}; any string of at least 32 characters
 * @param allowInsecureUrls true allows http:// and loopback receivers, for local testing only
 */
@Validated
@ConfigurationProperties("swiftdrop.webhooks")
public record WebhookProperties(
        @NotBlank(message = "WEBHOOK_ENCRYPTION_KEY (or JWT_SECRET) is missing. Set it in .env (at least 32 characters).")
        @Size(min = 32, message = "WEBHOOK_ENCRYPTION_KEY must be at least 32 characters long (or leave it empty).")
        String encryptionKey,
        boolean allowInsecureUrls) {

    /** Keeps the key out of logs. */
    @Override
    public String toString() {
        return "WebhookProperties[encryptionKey=" + (encryptionKey == null || encryptionKey.isBlank() ? "missing" : "set")
                + ", allowInsecureUrls=" + allowInsecureUrls + "]";
    }
}
