package com.swiftdrop.shared.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sri Lankan mobile numbers. Accepts {@code 0771234567}, {@code 771234567} and {@code +94771234567},
 * with spaces anywhere, and returns the stored form {@code +94771234567}.
 * Used by both register and login so the two always agree.
 */
public final class PhoneNumberNormalizer {

    private static final Pattern MOBILE = Pattern.compile("^(?:\\+94|0)?(7\\d{8})$");

    private PhoneNumberNormalizer() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = raw.replaceAll("\\s+", "");
        Matcher matcher = MOBILE.matcher(compact);
        return matcher.matches() ? Optional.of("+94" + matcher.group(1)) : Optional.empty();
    }

    public static boolean isValid(String raw) {
        return normalize(raw).isPresent();
    }
}
