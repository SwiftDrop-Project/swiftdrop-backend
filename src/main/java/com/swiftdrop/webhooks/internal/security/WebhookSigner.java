package com.swiftdrop.webhooks.internal.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The {@code X-SwiftDrop-Signature} header of API.md "Webhooks":
 * {@code sha256=} + lowercase hex HMAC-SHA256(key = the whole secret incl. {@code whsec_} as UTF-8,
 * message = {@code <timestamp>.<raw body>}). Sign the body exactly as it is sent; never re-serialize it.
 */
@Component
public class WebhookSigner {

    static final String PREFIX = "sha256=";
    private static final String HMAC = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    public String sign(String secret, long timestampSeconds, String rawBody) {
        return PREFIX + HEX.formatHex(mac(secret, timestampSeconds, rawBody));
    }

    /**
     * Constant-time check of a signature header, the way a merchant verifies it. SwiftDrop only sends, so this is
     * used by tests and serves as the reference for merchants.
     */
    public boolean matches(String secret, long timestampSeconds, String rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        String hex = signatureHeader.substring(PREFIX.length());
        if (!hex.equals(hex.toLowerCase())) {
            return false;
        }
        byte[] given;
        try {
            given = HEX.parseHex(hex);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(given, mac(secret, timestampSeconds, rawBody));
    }

    private static byte[] mac(String secret, long timestampSeconds, String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal((timestampSeconds + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }
}
