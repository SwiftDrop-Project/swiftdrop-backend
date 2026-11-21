package com.swiftdrop.delivery.internal.storage;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short, safe description of a Cloudinary failure for the log, so a refusal can be diagnosed ("permission denied
 * (missing: create)") without ever logging what must stay private: credentials, URLs (signed links carry signatures),
 * Cloudinary's internal environment ids, public ids or image data.
 *
 * <p>Known Cloudinary answers become fixed categories; anything else keeps only Cloudinary's wording with URLs, ids and
 * long numbers removed, cut to a short length. The courier still only gets a plain 503.
 */
final class CloudinaryErrors {

    private static final int MAX_DETAIL = 120;

    private static final Pattern ACTIONS = Pattern.compile("actions=\\[([^\\]]*)\\]");
    private static final Pattern STATUS = Pattern.compile("unexpected status code - (\\d{3})");

    private CloudinaryErrors() {
    }

    /** What went wrong, safe to log. */
    static String describe(Throwable ex) {
        if (ex instanceof IOException || ex.getCause() instanceof IOException) {
            Throwable io = ex instanceof IOException ? ex : ex.getCause();
            return "network error (" + io.getClass().getSimpleName() + ")";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "unexpected error (" + ex.getClass().getSimpleName() + ")";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("missing permissions")) {
            Matcher actions = ACTIONS.matcher(message);
            String missing = actions.find() ? actions.group(1).replaceAll("[^A-Za-z_,]", "") : "";
            return missing.isEmpty() ? "permission denied" : "permission denied (missing: " + missing + ")";
        }
        if (lower.contains("invalid signature") || lower.contains("unknown api key") || lower.contains("invalid api_key")
                || lower.contains("api_secret mismatch") || lower.contains("must supply api")) {
            return "authentication failed (check the API key and secret)";
        }
        if (lower.contains("stale request")) {
            return "request time refused (check the server clock)";
        }
        if (lower.contains("rate limit")) {
            return "rate limited";
        }
        if (lower.contains("file size too large")) {
            return "file too large for the Cloudinary plan";
        }
        if (lower.contains("invalid image file")) {
            return "image refused by Cloudinary";
        }
        if (lower.contains("not found")) {
            return "not found";
        }
        Matcher status = STATUS.matcher(lower);
        if (status.find()) {
            return "unexpected HTTP status " + status.group(1);
        }
        return "refused: " + sanitize(message);
    }

    /** Cloudinary's wording without URLs, environment ids, ids or long numbers (API keys are long numbers). */
    static String sanitize(String message) {
        String safe = message
                .replaceAll("\\[prodenv:[^\\]]*\\]", "")
                .replaceAll("(?i)[a-z][a-z0-9+.-]*://\\S+", "<url>")
                .replaceAll("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "<id>")
                .replaceAll("(?i)\\b[0-9a-f]{16,}\\b", "<id>")
                .replaceAll("\\d{6,}", "<n>")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return safe.length() <= MAX_DETAIL ? safe : safe.substring(0, MAX_DETAIL) + "...";
    }
}
