package com.swiftdrop.notifications.internal.application;

/** Hides most of an email address, e.g. {@code ayesha@gmail.com} → {@code a•••@gmail.com}. */
public final class EmailMasking {

    private static final String DOTS = "•••";

    private EmailMasking() {
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return DOTS;
        }
        String value = email.trim();
        int at = value.lastIndexOf('@');
        if (at <= 0) {
            return value.charAt(0) + DOTS;
        }
        return value.charAt(0) + DOTS + value.substring(at);
    }
}
