package com.swiftdrop.merchants;

import com.swiftdrop.CapturingMailSender.Captured;
import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.MerchantProfiles.SignUp;
import com.swiftdrop.merchants.internal.application.MerchantService;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The merchant is emailed the admin's decision after an approval or a rejection (and the demo seeder emails no one). */
class MerchantDecisionEmailTest extends IntegrationTest {

    private static final String APPROVED_SUBJECT = "Your SwiftDrop merchant account is approved";
    private static final String REJECTED_SUBJECT = "Your SwiftDrop merchant application";

    @Autowired
    MerchantService merchantService;

    @Test
    void approvingEmailsTheMerchant() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());

        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());

        Captured message = mail.awaitMessages(email, APPROVED_SUBJECT, 1).getFirst();
        assertThat(message.text()).contains("Hi Nimal Perera,").contains("Lanka Crafts").contains("log in");
        assertThat(message.html()).contains("Lanka Crafts");
        assertThat(mail.messagesTo(email)).noneMatch(m -> REJECTED_SUBJECT.equals(m.subject()));
    }

    @Test
    void rejectingEmailsTheMerchant() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());

        mvc.perform(post("/api/v1/admin/merchants/{id}/reject", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());

        Captured message = mail.awaitMessages(email, REJECTED_SUBJECT, 1).getFirst();
        assertThat(message.text()).contains("Hi Nimal Perera,").contains("Lanka Crafts").contains("couldn't approve");
        assertThat(mail.messagesTo(email)).noneMatch(m -> APPROVED_SUBJECT.equals(m.subject()));
    }

    @Test
    void aFailingMailServerDoesNotUndoTheDecision() throws Exception {
        String userId = registerMerchant(uniqueEmail(), uniquePhone());
        mail.failSending(true);

        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownMerchantSendsNoEmail() throws Exception {
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", UUID.randomUUID()).cookie(loginAsAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void seedingADemoMerchantEmailsNoOne() {
        String email = uniqueEmail();

        merchantService.seedApprovedMerchant("Demo Owner", email, stored(uniquePhone()), PASSWORD,
                new SignUp("Demo Shop", null, ParcelsPerMonth.UNDER_50, District.COLOMBO, "Colombo"),
                UUID.randomUUID());

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> mail.messagesTo(email).isEmpty());
    }
}
