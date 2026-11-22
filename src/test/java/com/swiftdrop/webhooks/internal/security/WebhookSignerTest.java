package com.swiftdrop.webhooks.internal.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test";
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final String BODY = "{\"id\":\"e1\"}";
    /**
     * Computed outside Java, so the test doesn't just repeat the code under test:
     * printf '%s' '1700000000.{"id":"e1"}' | openssl dgst -sha256 -hmac 'whsec_test'
     */
    private static final String KNOWN_SIGNATURE =
            "sha256=6a85cb117c993612a34c72b4cfb5a16baed25a80d26e298ce0e6671320fd07c8";

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void matchesAValueComputedWithOpenssl() {
        assertThat(signer.sign(SECRET, TIMESTAMP, BODY)).isEqualTo(KNOWN_SIGNATURE);
    }

    @Test
    void isSha256PrefixAndLowercaseHex() {
        assertThat(signer.sign("whsec_other", 1L, "{}")).matches("sha256=[0-9a-f]{64}");
    }

    @Test
    void anyChangeChangesTheSignature() {
        assertThat(signer.sign(SECRET, TIMESTAMP, BODY + " ")).isNotEqualTo(KNOWN_SIGNATURE);
        assertThat(signer.sign(SECRET, TIMESTAMP + 1, BODY)).isNotEqualTo(KNOWN_SIGNATURE);
        assertThat(signer.sign("whsec_tesT", TIMESTAMP, BODY)).isNotEqualTo(KNOWN_SIGNATURE);
        // the whsec_ prefix is part of the key
        assertThat(signer.sign("test", TIMESTAMP, BODY)).isNotEqualTo(KNOWN_SIGNATURE);
    }

    @Test
    void verifiesLikeAMerchantWould() {
        assertThat(signer.matches(SECRET, TIMESTAMP, BODY, KNOWN_SIGNATURE)).isTrue();

        assertThat(signer.matches(SECRET, TIMESTAMP, BODY, KNOWN_SIGNATURE.replace("sha256=", ""))).isFalse();
        assertThat(signer.matches(SECRET, TIMESTAMP, BODY, KNOWN_SIGNATURE.toUpperCase())).isFalse();
        assertThat(signer.matches(SECRET, TIMESTAMP, BODY, "sha256=not-hex")).isFalse();
        assertThat(signer.matches(SECRET, TIMESTAMP, BODY, null)).isFalse();
        assertThat(signer.matches(SECRET, TIMESTAMP + 1, BODY, KNOWN_SIGNATURE)).isFalse();
        assertThat(signer.matches("whsec_wrong", TIMESTAMP, BODY, KNOWN_SIGNATURE)).isFalse();
    }
}
