package com.swiftdrop.webhooks.internal.security;

import com.swiftdrop.FakeHostResolver;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static com.swiftdrop.webhooks.internal.security.CallbackUrlValidator.INVALID_URL;
import static com.swiftdrop.webhooks.internal.security.CallbackUrlValidator.NOT_ALLOWED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** URLs are checked with FakeHostResolver: no real DNS. */
class CallbackUrlValidatorTest {

    private static final String KEY = "test-only-webhook-key-0123456789-abcdefghij";

    private final CallbackUrlValidator strict =
            new CallbackUrlValidator(new WebhookProperties(KEY, false), new FakeHostResolver());
    private final CallbackUrlValidator insecureAllowed =
            new CallbackUrlValidator(new WebhookProperties(KEY, true), new FakeHostResolver());

    // ---------- allowed ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://shop.example.com/hook",
            "https://shop.example.com:8443/hook?x=1",
            "HTTPS://Shop.Example.com/hook",
            "https://xn--bcher-kva.example/hook",
            "https://bücher.example/hook",
            "https://bücher.example:8443/hook",
            "https://93.184.216.34/hook",
            "https://[2606:2800:220:1:248:1893:25c8:1946]/hook"})
    void publicHttpsUrlsAreAllowed(String url) {
        assertThat(strict.validateForRegistration(url)).isEqualTo(URI.create(url));
        assertThat(strict.isAllowedNow(url)).isTrue();
        assertThat(insecureAllowed.isAllowedNow(url)).isTrue();
    }

    // ---------- not a usable https URL ----------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "shop.example.com/hook", "ftp://shop.example.com/hook", "https://", "https:///hook",
            "https://shop example.com/hook", "mailto:shop@example.com", "http://shop.example.com/hook"})
    void invalidUrlsAskForAFullHttpsAddress(String url) {
        assertRejected(strict, url, INVALID_URL);
    }

    @Test
    void urlsLongerThan2048CharactersAreRejected() {
        String url = "https://shop.example.com/" + "a".repeat(2048 - 25);
        assertThat(url).hasSize(2048);
        assertThat(strict.isAllowedNow(url)).isTrue();

        assertRejected(strict, url + "a", INVALID_URL);
    }

    // ---------- SSRF ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",
            "https://127.1.2.3/hook",
            "https://[::1]/hook",
            "https://localhost/hook",
            "https://api.loopback.test/hook",
            "https://10.0.0.5/hook",
            "https://172.16.0.1/hook",
            "https://172.31.255.255/hook",
            "https://192.168.1.10/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://[fe80::1]/hook",
            "https://100.64.0.1/hook",
            "https://100.127.255.255/hook",
            "https://[fd00::1]/hook",
            "https://[fc00::1]/hook",
            "https://0.0.0.0/hook",
            "https://0.1.2.3/hook",
            "https://[::]/hook",
            "https://224.0.0.1/hook",
            "https://198.18.0.1/hook",
            "https://240.0.0.1/hook",
            "https://255.255.255.255/hook",
            "https://[::ffff:127.0.0.1]/hook",
            "https://[::10.0.0.5]/hook",
            "https://api.private.test/hook",
            "https://api.mixed.test/hook",
            "https://api.dns-down.test/hook",
            "https://user:pw@shop.example.com/hook",
            "https://user@shop.example.com/hook"})
    void internalAddressesAndUserInfoAreRejected(String url) {
        assertRejected(strict, url, NOT_ALLOWED);
        assertThat(strict.isAllowedNow(url)).isFalse();
    }

    @Test
    void nearbyPublicRangesAreNotCaughtByMistake() {
        assertThat(strict.isAllowedNow("https://100.128.0.1/hook")).isTrue(); // just above 100.64.0.0/10
        assertThat(strict.isAllowedNow("https://172.32.0.1/hook")).isTrue();  // just above 172.16.0.0/12
        assertThat(strict.isAllowedNow("https://198.20.0.1/hook")).isTrue();  // just above 198.18.0.0/15
        assertThat(strict.isAllowedNow("https://11.0.0.1/hook")).isTrue();
    }

    // ---------- allow-insecure-urls (local testing) ----------

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9000/hook", "http://127.0.0.1:9000/hook", "https://[::1]/hook",
            "http://shop.example.com/hook"})
    void insecureModeAllowsHttpAndLoopback(String url) {
        assertThat(insecureAllowed.validateForRegistration(url)).isEqualTo(URI.create(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://10.0.0.5/hook", "https://192.168.1.10/hook", "http://169.254.169.254/",
            "http://api.private.test/hook", "http://api.mixed.test/hook", "http://user:pw@localhost:9000/hook"})
    void insecureModeStillBlocksPrivateAndLinkLocal(String url) {
        assertRejected(insecureAllowed, url, NOT_ALLOWED);
    }

    @Test
    void insecureModeStillNeedsHttpOrHttps() {
        assertRejected(insecureAllowed, "ftp://localhost/hook", INVALID_URL);
    }

    // ---------- the address check itself ----------

    @Test
    void publicAddressCheck() throws UnknownHostException {
        assertThat(CallbackUrlValidator.isPublic(InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(CallbackUrlValidator.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(CallbackUrlValidator.isPublic(InetAddress.getByName("2001:4860:4860::8888"))).isTrue();
        assertThat(CallbackUrlValidator.isPublic(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(CallbackUrlValidator.isPublic(InetAddress.getByName("fd12:3456::1"))).isFalse();
    }

    private static void assertRejected(CallbackUrlValidator validator, String url, String message) {
        assertThatThrownBy(() -> validator.validateForRegistration(url))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.fieldErrors()).containsExactlyEntriesOf(
                                java.util.Map.of("url", message)));
    }
}
