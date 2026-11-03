package com.swiftdrop.identity.internal.security;

import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeHasherTest {

    private static final SecurityProperties SECURITY = new SecurityProperties("http://localhost:3000", false,
            new SecurityProperties.Jwt("test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz", 60, 7), null);

    private final CodeHasher hasher = new CodeHasher(new VerificationCodeProperties(null), SECURITY);

    @Test
    void codesAreSixDigitsAndVary() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = hasher.newCode();
            assertThat(code).matches("\\d{6}");
            codes.add(code);
        }
        assertThat(codes.size()).isGreaterThan(190);
    }

    @Test
    void hashIsBoundToUserPurposeAndCode() {
        UUID user = UUID.randomUUID();
        String hash = hasher.hash(user, VerificationPurpose.EMAIL_VERIFICATION, "123456");

        assertThat(hash).matches("[0-9a-f]{64}").doesNotContain("123456");
        assertThat(hasher.matches(hash, user, VerificationPurpose.EMAIL_VERIFICATION, "123456")).isTrue();
        assertThat(hasher.matches(hash, user, VerificationPurpose.EMAIL_VERIFICATION, "123457")).isFalse();
        assertThat(hasher.matches(hash, user, VerificationPurpose.PASSWORD_RESET, "123456")).isFalse();
        assertThat(hasher.matches(hash, UUID.randomUUID(), VerificationPurpose.EMAIL_VERIFICATION, "123456")).isFalse();
        assertThat(hasher.matches("not-hex", user, VerificationPurpose.EMAIL_VERIFICATION, "123456")).isFalse();
    }

    @Test
    void anExplicitSecretGivesDifferentHashesThanTheDerivedKey() {
        UUID user = UUID.randomUUID();
        CodeHasher explicit = new CodeHasher(
                new VerificationCodeProperties("another-secret-0123456789-abcdefghijklmnop"), SECURITY);

        assertThat(explicit.hash(user, VerificationPurpose.PASSWORD_RESET, "000000"))
                .isNotEqualTo(hasher.hash(user, VerificationPurpose.PASSWORD_RESET, "000000"));
    }
}
