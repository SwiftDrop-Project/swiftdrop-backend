package com.swiftdrop.delivery.internal.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

/** Cloudinary failures become short, safe log text: categories for known answers, never secrets, URLs or ids. */
class CloudinaryErrorsTest {

    private static String describe(String sdkMessage) {
        return CloudinaryErrors.describe(new RuntimeException(sdkMessage));
    }

    @Test
    void knownCloudinaryAnswersBecomeCategories() {
        assertThat(describe("[prodenv:abcdef0123456789abcdef01234567] Request forbidden due to missing permissions "
                + "(actions=[\"create\"])")).isEqualTo("permission denied (missing: create)");
        assertThat(describe("Request forbidden due to missing permissions (actions=[\"read\",\"delete\"])"))
                .isEqualTo("permission denied (missing: read,delete)");
        assertThat(describe("Invalid Signature abc. String to sign - 'timestamp=1'.")).startsWith("authentication failed");
        assertThat(describe("Unknown API key 123456789012345")).startsWith("authentication failed")
                .doesNotContain("123456789012345");
        assertThat(describe("Stale request - reported time is 2026-11-21 04:00:00 +0000")).startsWith("request time");
        assertThat(describe("Rate Limit Exceeded")).isEqualTo("rate limited");
        assertThat(describe("File size too large. Got 99999999. Maximum is 10485760.")).startsWith("file too large");
        assertThat(describe("Invalid image file")).isEqualTo("image refused by Cloudinary");
        assertThat(describe("Resource not found - swiftdrop-local/proof-of-delivery/3f1d2c4b")).isEqualTo("not found");
        assertThat(describe("Server returned unexpected status code - 502 - <html>bad gateway</html>"))
                .isEqualTo("unexpected HTTP status 502");
    }

    @Test
    void networkFailuresNameOnlyTheExceptionType() {
        assertThat(CloudinaryErrors.describe(new ConnectException("Connection refused: api.cloudinary.com/1.2.3.4")))
                .isEqualTo("network error (ConnectException)");
        assertThat(CloudinaryErrors.describe(new RuntimeException(new IOException("x"))))
                .isEqualTo("network error (IOException)");
        assertThat(CloudinaryErrors.describe(new IllegalStateException())).isEqualTo("unexpected error (IllegalStateException)");
    }

    @Test
    void anUnknownAnswerKeepsOnlyCloudinarysWordsWithoutSecretsUrlsOrIds() {
        String secret = "s3cr3tValueNotReal";
        String text = describe("Something odd for cloudinary://123456789012345:" + secret + "@demo-cloud and "
                + "https://api.cloudinary.com/v1_1/demo/image/download?api_key=123456789012345&signature=0a1b2c3d4e5f60718293 "
                + "public id 3f1d2c4b-1111-2222-3333-444455556666 [prodenv:abcdef0123456789abcdef01234567]\nnext line");

        assertThat(text).startsWith("refused: Something odd for <url>")
                .doesNotContain(secret).doesNotContain("123456789012345").doesNotContain("signature")
                .doesNotContain("3f1d2c4b").doesNotContain("prodenv").doesNotContain("\n");
        assertThat(describe("x".repeat(500))).hasSizeLessThanOrEqualTo("refused: ".length() + 123);
    }
}
