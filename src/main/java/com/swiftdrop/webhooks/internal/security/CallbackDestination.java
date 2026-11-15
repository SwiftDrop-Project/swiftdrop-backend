package com.swiftdrop.webhooks.internal.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a callback URL actually sends requests, for the rule "a merchant can't have two active subscriptions for the
 * same destination". Deliberately conservative: two URLs are the same only when the difference can't change where a
 * request goes, so genuinely different endpoints are never treated as duplicates.
 *
 * <p>Ignored (treated as equal): spaces around the URL, letter case of the scheme and host, a non-Latin host vs its
 * {@code xn--} name, a default port written out ({@code :443} for https, {@code :80} for http), an empty path vs
 * {@code /}, and a {@code #fragment} (never sent over HTTP).
 *
 * <p>Kept (treated as different): the path (case-sensitive, including a trailing slash: {@code /hook} is not
 * {@code /hook/}), the query exactly as written (including order), {@code http} vs {@code https}, any other port,
 * and percent-encoding (not decoded).
 */
public final class CallbackDestination {

    private final String scheme;
    private final String host;
    private final int port;
    private final String path;
    private final String query;
    /** Only set when the URL couldn't be parsed: then it's compared as written (trimmed). */
    private final String unparsed;

    private CallbackDestination(String scheme, String host, int port, String path, String query, String unparsed) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.path = path;
        this.query = query;
        this.unparsed = unparsed;
    }

    /** The destination of {@code url}. Never fails: a URL that can't be parsed is compared as written. */
    public static CallbackDestination of(String url) {
        String trimmed = url == null ? "" : url.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            Optional<String> host = uri.getRawAuthority() == null ? Optional.empty() : CallbackUrlValidator.asciiHost(uri);
            if (scheme == null || host.isEmpty()) {
                return unparsed(trimmed);
            }
            String rawPath = uri.getRawPath();
            String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
            return new CallbackDestination(scheme, host.get(), effectivePort(scheme, uri.getPort()), path,
                    uri.getRawQuery(), null);
        } catch (URISyntaxException ex) {
            return unparsed(trimmed);
        }
    }

    /** True when both URLs send their requests to the same place (see the class comment for what is ignored). */
    public static boolean sameDestination(String first, String second) {
        return of(first).equals(of(second));
    }

    private static CallbackDestination unparsed(String trimmed) {
        return new CallbackDestination(null, null, -1, null, null, trimmed);
    }

    /** No port written means the scheme's default, so "https://a" and "https://a:443" are the same connection. */
    private static int effectivePort(String scheme, int port) {
        if (port != -1) {
            return port;
        }
        return switch (scheme) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CallbackDestination that)) {
            return false;
        }
        return port == that.port && Objects.equals(scheme, that.scheme) && Objects.equals(host, that.host)
                && Objects.equals(path, that.path) && Objects.equals(query, that.query)
                && Objects.equals(unparsed, that.unparsed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, host, port, path, query, unparsed);
    }

    /** Scheme, host and port only: the path and query can hold tokens, so they never reach a log. */
    @Override
    public String toString() {
        return unparsed != null ? "CallbackDestination[unparsed]"
                : "CallbackDestination[" + scheme + "://" + host + ":" + port + "]";
    }
}
