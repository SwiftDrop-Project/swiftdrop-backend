package com.swiftdrop.webhooks.internal.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts webhook signing secrets for webhooks.subscriptions.secret_ciphertext. They must be readable again at send
 * time (to sign), so they are encrypted, not hashed.
 *
 * <p>AES-256-GCM with a fresh random 12-byte IV per encryption. Stored as {@code v1:} + base64(IV + ciphertext + tag);
 * the version prefix leaves room for another key or algorithm later. The AES key is
 * HMAC-SHA256(encryption-key, "swiftdrop-webhook-secrets"), so it differs from the JWT key even when encryption-key
 * falls back to the JWT secret. Exception messages never contain the secret, the stored value or the key.
 */
@Component
public class SecretCipher {

    static final String FORMAT_PREFIX = "v1:";
    private static final String DERIVATION_LABEL = "swiftdrop-webhook-secrets";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(WebhookProperties properties) {
        this.key = new SecretKeySpec(deriveKey(properties.encryptionKey()), "AES");
    }

    public String encrypt(String plainSecret) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION); // not thread-safe: one per call
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("A webhook secret could not be encrypted", ex);
        }
    }

    /** Throws IllegalStateException for a changed value, a wrong key or an unknown format. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(FORMAT_PREFIX)) {
            throw new IllegalStateException("A webhook secret has an unknown format");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stored.substring(FORMAT_PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("A webhook secret has an unknown format");
        }
        if (bytes.length <= IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("A webhook secret has an unknown format");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            // AEADBadTagException: changed ciphertext or another key. No cause details in the message.
            throw new IllegalStateException("A webhook secret could not be decrypted");
        }
    }

    private static byte[] deriveKey(String keyMaterial) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyMaterial.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8)); // 32 bytes = AES-256
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }
}
