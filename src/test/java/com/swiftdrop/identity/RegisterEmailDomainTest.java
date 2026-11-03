package com.swiftdrop.identity;

import com.swiftdrop.FakeMxLookup;
import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-up email checks. DNS is faked (FakeMxLookup): no network in tests. */
class RegisterEmailDomainTest extends IntegrationTest {

    @Test
    void domainWithoutMxRecordsIsRejected() throws Exception {
        register(customerBody("ayesha@gmial." + FakeMxLookup.NO_MX_DOMAIN, uniquePhone()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").value("We couldn't find this email domain. Check for typos."));
    }

    @Test
    void disposableDomainsAndTheirSubdomainsAreRejected() throws Exception {
        for (String email : new String[]{"someone@mailinator.com", "someone@eu.YOPMAIL.com"}) {
            register(merchantBody(email, uniquePhone()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.email").value("Use a permanent email address."));
        }
    }

    @Test
    void whenDnsFailsTheAddressIsAllowed() throws Exception {
        register(customerBody("user-" + UUID.randomUUID().toString().substring(0, 8) + "@x." + FakeMxLookup.DNS_DOWN_DOMAIN,
                uniquePhone()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_EMAIL_VERIFICATION"));
    }

    @Test
    void badSyntaxStillGetsTheSyntaxMessageOnly() throws Exception {
        register(customerBody("name@example", uniquePhone()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("Enter a full email address, like name@example.com"));
    }
}
