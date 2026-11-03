package com.swiftdrop.identity.internal.security;

import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Creates one-time 6-digit codes and their HMAC-SHA256 hashes. The hash covers user id, purpose and code, so a
 * stored hash is useless for another user or purpose, and a leaked table cannot be brute-forced without the key.
 *
 * <p>Key: VERIFICATION_CODE_SECRET, or (when blank) HMAC-SHA256(JWT_SECRET, "swiftdrop-verification-codes").
 */
@Component
public class CodeHasher {

    private static final String HMAC = "HmacSHA256";
    private static final String DERIVATION_LABEL = "swiftdrop-verification-codes";
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public CodeHasher(VerificationCodeProperties codeProperties, SecurityProperties securityProperties) {
        byte[] keyBytes = codeProperties.hasSecret()
                ? codeProperties.secret().getBytes(StandardCharsets.UTF_8)
                : hmac(securityProperties.jwt().secret().getBytes(StandardCharsets.UTF_8), DERIVATION_LABEL);
        this.key = new SecretKeySpec(keyBytes, HMAC);
    }

    /** A uniformly random code from 000000 to 999999. */
    public String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** 64 hex characters. */
    public String hash(UUID userId, VerificationPurpose purpose, String code) {
        return HEX.formatHex(mac(userId, purpose, code));
    }

    /** Constant-time comparison of {@code code} against a stored hash. */
    public boolean matches(String storedHash, UUID userId, VerificationPurpose purpose, String code) {
        byte[] expected;
        try {
            expected = HEX.parseHex(storedHash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, mac(userId, purpose, code));
    }

    private byte[] mac(UUID userId, VerificationPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal((userId + ":" + purpose.name() + ":" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    private static byte[] hmac(byte[] keyBytes, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(keyBytes, HMAC));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }
}
