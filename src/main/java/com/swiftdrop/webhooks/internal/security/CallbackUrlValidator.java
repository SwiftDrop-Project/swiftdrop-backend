package com.swiftdrop.webhooks.internal.security;

import com.swiftdrop.shared.error.FieldValidationException;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps webhook URLs away from SwiftDrop's own network (SSRF): https only, no user info, and every address the host
 * resolves to must be public (not loopback, private, link-local such as the cloud metadata address 169.254.169.254,
 * carrier-grade NAT, IPv6 unique-local, multicast or reserved). With allow-insecure-urls (local testing only),
 * http and loopback receivers are allowed too; private and link-local addresses never are.
 *
 * <p>Checked when a URL is saved ({@link #validateForRegistration}) and again just before every send
 * ({@link #isAllowedNow}), because a host's DNS answer can change after registration (DNS rebinding). A small gap
 * between that last check and the connection remains (closing it would mean pinning the connection to the checked
 * address); with redirects turned off in the HTTP client, this is the level of protection chosen.
 */
@Component
public class CallbackUrlValidator {

    /** API.md "Webhooks" validation messages. */
    static final String FIELD = "url";
    static final String INVALID_URL = "Enter a full https:// address, like https://shop.example.com/webhooks";
    static final String NOT_ALLOWED = "This address can't receive webhooks. Use a public https:// address.";
    static final int MAX_LENGTH = 2048;

    private final WebhookProperties properties;
    private final HostResolver resolver;

    public CallbackUrlValidator(WebhookProperties properties, HostResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    /** Throws 400 VALIDATION_FAILED with {@code fieldErrors.url} when the URL may not receive webhooks. */
    public URI validateForRegistration(String url) {
        Optional<String> problem = check(url);
        if (problem.isPresent()) {
            throw new FieldValidationException(FIELD, problem.get());
        }
        return URI.create(url);
    }

    /** The check before a send: false when the URL (or what its host resolves to now) is not allowed. */
    public boolean isAllowedNow(String url) {
        return check(url).isEmpty();
    }

    /** Empty when allowed, otherwise the fieldErrors message. */
    private Optional<String> check(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {
            return Optional.of(INVALID_URL);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            return Optional.of(INVALID_URL);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeAllowed = scheme.equals("https") || (scheme.equals("http") && properties.allowInsecureUrls());
        if (!uri.isAbsolute() || !schemeAllowed || uri.getRawAuthority() == null) {
            return Optional.of(INVALID_URL);
        }
        if (uri.getRawAuthority().contains("@")) {
            return Optional.of(NOT_ALLOWED); // user info (https://user:pw@host)
        }
        Optional<String> host = asciiHost(uri);
        if (host.isEmpty()) {
            return Optional.of(INVALID_URL);
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host.get());
        } catch (UnknownHostException ex) {
            return Optional.of(NOT_ALLOWED);
        }
        if (addresses.isEmpty() || !addresses.stream().allMatch(this::allowed)) {
            return Optional.of(NOT_ALLOWED);
        }
        return Optional.empty();
    }

    /** Lower-case ASCII host without IPv6 brackets; non-Latin names become xn-- names. */
    private static Optional<String> asciiHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            // java.net.URI leaves the host empty for non-ASCII names: take it from the authority (minus the port)
            String authority = uri.getRawAuthority();
            int colon = authority.lastIndexOf(':');
            host = authority.startsWith("[") || colon < 0 ? authority : authority.substring(0, colon);
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private boolean allowed(InetAddress address) {
        if (properties.allowInsecureUrls() && address.isLoopbackAddress()) {
            return true;
        }
        return isPublic(address);
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            int third = b[2] & 0xff;
            return first != 0                                          // 0.0.0.0/8
                    && !(first == 100 && (second & 0xc0) == 64)        // 100.64.0.0/10 carrier-grade NAT
                    && !(first == 192 && second == 0 && third == 0)    // 192.0.0.0/24
                    && !(first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 benchmarking
                    && first < 240;                                    // 240.0.0.0/4 and 255.255.255.255
        }
        if (address instanceof Inet6Address v6) {
            if ((b[0] & 0xfe) == 0xfc) {
                return false;                                          // fc00::/7 unique local
            }
            if (v6.isIPv4CompatibleAddress()) {                        // ::a.b.c.d: check the IPv4 part
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException ex) {
                    return false;
                }
            }
        }
        return true;
    }
}
