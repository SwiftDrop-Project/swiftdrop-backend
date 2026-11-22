package com.swiftdrop.webhooks.internal.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSecretGeneratorTest {

    private final WebhookSecretGenerator generator = new WebhookSecretGenerator();

    @Test
    void secretsAreWhsecPlus43UrlSafeCharacters() {
        assertThat(generator.newSecret()).matches("whsec_[A-Za-z0-9_-]{43}").hasSize(49);
    }

    @Test
    void secretsAreUnique() {
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            secrets.add(generator.newSecret());
        }
        assertThat(secrets).hasSize(1000);
    }
}
