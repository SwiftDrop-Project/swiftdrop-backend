package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.merchants.MerchantSummary;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
import com.swiftdrop.webhooks.internal.dto.CreateWebhookRequest;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import com.swiftdrop.webhooks.internal.security.CallbackUrlValidator;
import com.swiftdrop.webhooks.internal.security.HostResolver;
import com.swiftdrop.webhooks.internal.security.SecretCipher;
import com.swiftdrop.webhooks.internal.security.WebhookProperties;
import com.swiftdrop.webhooks.internal.security.WebhookSecretGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The approval check of create. A merchant who isn't approved can't log in at all, so the API tests can't reach it:
 * it is tested here, without Spring.
 */
class WebhookSubscriptionServiceTest {

    private static final WebhookProperties PROPERTIES =
            new WebhookProperties("test-only-webhook-key-0123456789-abcdefghij", false);
    /** Fails the test if the URL check ever does a lookup: the approval check must come first. */
    private static final HostResolver NO_DNS = host -> {
        throw new AssertionError("The URL was checked before the approval");
    };
    private static final CreateWebhookRequest REQUEST =
            new CreateWebhookRequest("https://shop.example.com/hook", Set.of("parcel.delivered"));

    private final WebhookSubscriptionRepository repository = mock(WebhookSubscriptionRepository.class);

    @ParameterizedTest
    @EnumSource(value = ApprovalStatus.class, names = {"PENDING", "REJECTED"})
    void merchantsWhoArentApprovedCantSubscribe(ApprovalStatus status) {
        WebhookSubscriptionService service = service(userId -> Optional.of(
                new MerchantSummary(userId, "Lanka Crafts", District.COLOMBO, "Nugegoda",
                        ParcelsPerMonth.FROM_50_TO_200, status)));

        assertNotApproved(service);
    }

    @Test
    void usersWithoutAMerchantProfileCantSubscribe() {
        assertNotApproved(service(userId -> Optional.empty()));
    }

    private void assertNotApproved(WebhookSubscriptionService service) {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), REQUEST))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.code())
                                .isEqualTo(ErrorCode.MERCHANT_NOT_APPROVED));
        verifyNoInteractions(repository); // nothing counted, locked or saved
    }

    private WebhookSubscriptionService service(MerchantApi merchants) {
        return new WebhookSubscriptionService(repository, merchants, new CallbackUrlValidator(PROPERTIES, NO_DNS),
                new WebhookSecretGenerator(), new SecretCipher(PROPERTIES), Clock.systemUTC());
    }
}
