package com.swiftdrop.webhooks.internal.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/** Creates webhook signing secrets: {@code whsec_} + 32 random bytes, base64url without padding (49 characters). */
@Component
public class WebhookSecretGenerator {

    static final String PREFIX = "whsec_";
    private static final int RANDOM_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
