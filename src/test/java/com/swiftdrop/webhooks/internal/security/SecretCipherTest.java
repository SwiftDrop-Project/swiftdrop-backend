package com.swiftdrop.webhooks.internal.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY = "test-only-webhook-key-0123456789-abcdefghij";
    private static final String SECRET = "whsec_" + "A".repeat(43);

    private final SecretCipher cipher = new SecretCipher(new WebhookProperties(KEY, false));

    @Test
    void decryptsWhatItEncrypted() {
        assertThat(cipher.decrypt(cipher.encrypt(SECRET))).isEqualTo(SECRET);
    }

    @Test
    void storedValueIsVersionedHidesTheSecretAndFitsTheColumn() {
        String stored = cipher.encrypt(SECRET);

        assertThat(stored).startsWith("v1:").doesNotContain(SECRET).doesNotContain("whsec_");
        assertThat(stored.length()).isLessThanOrEqualTo(255); // webhooks.subscriptions.secret_ciphertext
    }

    @Test
    void theSameSecretEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt(SECRET)).isNotEqualTo(cipher.encrypt(SECRET)); // a fresh IV every time
    }

    @Test
    void aChangedValueIsRejected() {
        String stored = cipher.encrypt(SECRET);
        byte[] bytes = Base64.getDecoder().decode(stored.substring(3));
        bytes[bytes.length - 1] ^= 1; // flip one bit of the tag
        String tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A webhook secret could not be decrypted");
    }

    @Test
    void anotherKeyCantDecrypt() {
        SecretCipher other = new SecretCipher(new WebhookProperties(KEY + "-other", false));

        assertThatThrownBy(() -> other.decrypt(cipher.encrypt(SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void unknownFormatsAreRejected() {
        String stored = cipher.encrypt(SECRET);

        for (String bad : new String[] {null, "", stored.substring(3), "v2:" + stored.substring(3), "v1:not base64!",
                "v1:" + Base64.getEncoder().encodeToString(new byte[10])}) {
            assertThatThrownBy(() -> cipher.decrypt(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(SECRET);
        }
    }
}
