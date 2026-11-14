package com.swiftdrop.webhooks.internal.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Startup checks of WEBHOOK_ENCRYPTION_KEY, without the whole application. */
class WebhookPropertiesTest {

    private static final String KEY = "test-only-webhook-key-0123456789-abcdefghij";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void aLongEnoughKeyStarts() {
        runner.withPropertyValues("swiftdrop.webhooks.encryption-key=" + KEY,
                        "swiftdrop.webhooks.allow-insecure-urls=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebhookProperties properties = context.getBean(WebhookProperties.class);
                    assertThat(properties.encryptionKey()).isEqualTo(KEY);
                    assertThat(properties.allowInsecureUrls()).isTrue();
                });
    }

    @Test
    void insecureUrlsAreOffByDefault() {
        runner.withPropertyValues("swiftdrop.webhooks.encryption-key=" + KEY)
                .run(context -> assertThat(context.getBean(WebhookProperties.class).allowInsecureUrls()).isFalse());
    }

    @Test
    void aShortKeyStopsTheStartup() {
        runner.withPropertyValues("swiftdrop.webhooks.encryption-key=short")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("WEBHOOK_ENCRYPTION_KEY"));
    }

    @Test
    void aMissingKeyStopsTheStartup() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("WEBHOOK_ENCRYPTION_KEY"));
    }

    @Test
    void toStringHidesTheKey() {
        assertThat(new WebhookProperties(KEY, false).toString()).doesNotContain(KEY).contains("encryptionKey=set");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebhookProperties.class)
    static class Config {
    }
}
