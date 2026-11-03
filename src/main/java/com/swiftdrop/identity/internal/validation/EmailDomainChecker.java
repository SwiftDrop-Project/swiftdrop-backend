package com.swiftdrop.identity.internal.validation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.swiftdrop.identity.internal.validation.MxLookup.MxStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Sign-up checks on the email's domain. They catch typos and throw-away inboxes; the emailed code is what proves
 * the person owns the address.
 * <ol>
 *   <li>Disposable domains (and their subdomains) from {@code resources/email/disposable-domains.txt} are rejected.</li>
 *   <li>The domain must have MX records. If DNS itself fails, the address is allowed.</li>
 * </ol>
 * DNS answers are cached: domains with MX for 6 hours, domains without for 15 minutes; failures are not cached.
 */
@Component
public class EmailDomainChecker {

    public static final String NO_MAIL_DOMAIN = "We couldn't find this email domain. Check for typos.";
    public static final String DISPOSABLE = "Use a permanent email address.";

    private static final Duration FOUND_TTL = Duration.ofHours(6);
    private static final Duration NOT_FOUND_TTL = Duration.ofMinutes(15);

    private final MxLookup mxLookup;
    private final EmailCheckProperties properties;
    private final Set<String> disposableDomains;
    private final Cache<String, MxStatus> mxCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfter(Expiry.creating((String domain, MxStatus status) ->
                    status == MxStatus.FOUND ? FOUND_TTL : NOT_FOUND_TTL))
            .build();

    public EmailDomainChecker(MxLookup mxLookup, EmailCheckProperties properties) {
        this.mxLookup = mxLookup;
        this.properties = properties;
        this.disposableDomains = loadDisposableDomains();
    }

    /** The error message for {@code email}, or empty when it may be used. {@code email} must be syntactically valid. */
    public Optional<String> problemWith(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (isDisposable(domain)) {
            return Optional.of(DISPOSABLE);
        }
        if (properties.mxEnabled() && mxStatus(domain) == MxStatus.NOT_FOUND) {
            return Optional.of(NO_MAIL_DOMAIN);
        }
        return Optional.empty();
    }

    boolean isDisposable(String domain) {
        String candidate = domain;
        while (true) {
            if (disposableDomains.contains(candidate)) {
                return true;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0) {
                return false;
            }
            candidate = candidate.substring(dot + 1);
        }
    }

    private MxStatus mxStatus(String domain) {
        MxStatus cached = mxCache.getIfPresent(domain);
        if (cached != null) {
            return cached;
        }
        MxStatus status = mxLookup.lookup(domain);
        if (status != MxStatus.LOOKUP_FAILED) {
            mxCache.put(domain, status);
        }
        return status;
    }

    private static Set<String> loadDisposableDomains() {
        Set<String> domains = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("email/disposable-domains.txt").getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .forEach(domains::add);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read email/disposable-domains.txt", ex);
        }
        return Set.copyOf(domains);
    }
}
