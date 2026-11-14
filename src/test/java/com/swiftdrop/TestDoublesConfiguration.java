package com.swiftdrop;

import com.swiftdrop.identity.internal.validation.MxLookup;
import com.swiftdrop.webhooks.internal.security.HostResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test doubles for the outside world: SMTP (emails are captured) and DNS (MX answers and webhook host addresses by
 * name).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestDoublesConfiguration {

    @Bean
    CapturingMailSender capturingMailSender() {
        return new CapturingMailSender();
    }

    @Bean
    @Primary
    MxLookup fakeMxLookup() {
        return new FakeMxLookup();
    }

    @Bean
    @Primary
    HostResolver fakeHostResolver() {
        return new FakeHostResolver();
    }
}
