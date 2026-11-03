package com.swiftdrop.identity.internal.validation;

import com.swiftdrop.identity.internal.validation.MxLookup.MxStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDomainCheckerTest {

    private final Map<String, MxStatus> answers = new HashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private final MxLookup fake = domain -> {
        lookups.incrementAndGet();
        return answers.getOrDefault(domain, MxStatus.FOUND);
    };

    @Test
    void acceptsNormalDomainsAndCachesTheAnswer() {
        EmailDomainChecker checker = new EmailDomainChecker(fake, new EmailCheckProperties(true));

        assertThat(checker.problemWith("a@gmail.com")).isEmpty();
        assertThat(checker.problemWith("b@GMAIL.com")).isEmpty();
        assertThat(lookups).hasValue(1);
    }

    @Test
    void rejectsDomainsWithoutMx() {
        answers.put("gmial.com", MxStatus.NOT_FOUND);
        EmailDomainChecker checker = new EmailDomainChecker(fake, new EmailCheckProperties(true));

        assertThat(checker.problemWith("a@gmial.com")).contains(EmailDomainChecker.NO_MAIL_DOMAIN);
    }

    @Test
    void dnsFailuresAreAllowedAndNotCached() {
        answers.put("flaky.lk", MxStatus.LOOKUP_FAILED);
        EmailDomainChecker checker = new EmailDomainChecker(fake, new EmailCheckProperties(true));

        assertThat(checker.problemWith("a@flaky.lk")).isEmpty();
        assertThat(checker.problemWith("a@flaky.lk")).isEmpty();
        assertThat(lookups).hasValue(2);
    }

    @Test
    void disposableDomainsAreRejectedEvenWithoutTheDnsCheck() {
        EmailDomainChecker checker = new EmailDomainChecker(fake, new EmailCheckProperties(false));

        assertThat(checker.problemWith("a@mailinator.com")).contains(EmailDomainChecker.DISPOSABLE);
        assertThat(checker.problemWith("a@x.guerrillamail.com")).contains(EmailDomainChecker.DISPOSABLE);
        assertThat(checker.isDisposable("notmailinator.com")).isFalse();
        assertThat(checker.problemWith("a@nomx.lk")).isEmpty();
        assertThat(lookups).hasValue(0);
    }
}
