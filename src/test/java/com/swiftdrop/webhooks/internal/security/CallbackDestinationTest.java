package com.swiftdrop.webhooks.internal.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Same destination" for the one-active-subscription-per-URL rule. It must be conservative: only differences that
 * can't change where a request goes are ignored, so different endpoints are never reported as duplicates.
 */
class CallbackDestinationTest {

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource(delimiter = '|', textBlock = """
            https://shop.example.com/hook        | https://shop.example.com/hook
            https://shop.example.com/hook        | '  https://shop.example.com/hook  '
            https://shop.example.com/hook        | HTTPS://shop.example.com/hook
            https://shop.example.com/hook        | https://SHOP.Example.COM/hook
            https://shop.example.com/hook        | https://shop.example.com:443/hook
            http://localhost/hook                | http://localhost:80/hook
            https://shop.example.com             | https://shop.example.com/
            https://shop.example.com/hook        | https://shop.example.com/hook#orders
            https://shop.example.com/hook?a=1    | https://shop.example.com/hook?a=1#x
            https://bücher.example/hook          | https://xn--bcher-kva.example/hook
            https://[2001:db8::1]/hook           | https://[2001:DB8::1]:443/hook
            """)
    void theseAreTheSameDestination(String first, String second) {
        assertThat(CallbackDestination.sameDestination(first, second)).isTrue();
        assertThat(CallbackDestination.of(first)).hasSameHashCodeAs(CallbackDestination.of(second));
    }

    @ParameterizedTest(name = "{0} != {1}")
    @CsvSource(delimiter = '|', textBlock = """
            https://shop.example.com/hook        | https://shop.example.com/hook/
            https://shop.example.com/hook        | https://shop.example.com/Hook
            https://shop.example.com/hook        | https://shop.example.com/hook2
            https://shop.example.com/hook        | https://shop.example.com/hook?env=prod
            https://shop.example.com/hook?env=a  | https://shop.example.com/hook?env=b
            https://shop.example.com/hook?a=1&b=2 | https://shop.example.com/hook?b=2&a=1
            https://shop.example.com/hook?a=1    | https://shop.example.com/hook?A=1
            https://shop.example.com/hook        | http://shop.example.com/hook
            https://shop.example.com/hook        | https://shop.example.com:8443/hook
            https://shop.example.com/hook        | https://staging.shop.example.com/hook
            https://shop.example.com/hook        | https://shop.example.org/hook
            https://shop.example.com/a%2Fb       | https://shop.example.com/a/b
            https://shop.example.com/~me         | https://shop.example.com/%7Eme
            """)
    void theseAreDifferentDestinations(String first, String second) {
        assertThat(CallbackDestination.sameDestination(first, second)).isFalse();
    }

    @Test
    void aUrlThatCantBeParsedIsComparedAsWritten() {
        assertThat(CallbackDestination.sameDestination("not a url", "not a url")).isTrue();
        assertThat(CallbackDestination.sameDestination("not a url", "https://shop.example.com/hook")).isFalse();
        assertThat(CallbackDestination.sameDestination(null, "")).isTrue();
    }

    @Test
    void toStringNeverShowsThePathOrQuery() {
        assertThat(CallbackDestination.of("https://shop.example.com/hook/secret-token?key=abc").toString())
                .contains("shop.example.com")
                .doesNotContain("secret-token")
                .doesNotContain("abc");
    }
}
