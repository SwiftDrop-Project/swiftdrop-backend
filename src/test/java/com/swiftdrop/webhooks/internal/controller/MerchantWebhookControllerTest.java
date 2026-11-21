package com.swiftdrop.webhooks.internal.controller;

import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.webhooks.internal.application.WebhookDeliveryService;
import com.swiftdrop.webhooks.internal.application.WebhookSubscriptionService;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.dto.UpdateWebhookRequest;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A clash with another change to the same subscription (optimistic lock) becomes 409 WEBHOOK_CHANGED, never a 500.
 * The API tests send real requests at the same moment, but whether they overlap depends on timing; these tests make
 * the clash happen every time.
 */
class MerchantWebhookControllerTest {

    private final UUID merchantId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final WebhookSubscriptionService service = mock(WebhookSubscriptionService.class);
    private final MerchantWebhookController controller =
            new MerchantWebhookController(service, mock(WebhookDeliveryService.class));
    private final JwtAuthenticationToken merchant = new JwtAuthenticationToken(Jwt.withTokenValue("token")
            .header("alg", "HS256").subject(merchantId.toString()).build());

    @Test
    void aClashWhileUpdatingIsWebhookChanged() {
        UpdateWebhookRequest request = new UpdateWebhookRequest(null, Set.of("parcel.failed"), null);
        when(service.update(eq(merchantId), eq(id), any())).thenThrow(clash());

        assertThatThrownBy(() -> controller.update(merchant, id, request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.WEBHOOK_CHANGED));
    }

    @Test
    void aClashWhileRotatingIsWebhookChanged() {
        when(service.rotateSecret(merchantId, id)).thenThrow(clash());

        assertThatThrownBy(() -> controller.rotateSecret(merchant, id))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.WEBHOOK_CHANGED));
    }

    @Test
    void aClashWhileTurningOffIsFineWhenItEndedUpOff() {
        doThrow(clash()).when(service).deactivate(merchantId, id);
        when(service.isActive(merchantId, id)).thenReturn(false);

        assertThat(controller.deactivate(merchant, id).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void aClashWhileTurningOffIsWebhookChangedWhenSomeoneTurnedItBackOn() {
        doThrow(clash()).when(service).deactivate(merchantId, id);
        when(service.isActive(merchantId, id)).thenReturn(true);

        assertThatThrownBy(() -> controller.deactivate(merchant, id))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.WEBHOOK_CHANGED));
    }

    @Test
    void otherErrorsAreNotTurnedIntoWebhookChanged() {
        IllegalStateException other = new IllegalStateException("something else");
        when(service.rotateSecret(merchantId, id)).thenThrow(other);

        assertThatThrownBy(() -> controller.rotateSecret(merchant, id)).isSameAs(other);
    }

    private ObjectOptimisticLockingFailureException clash() {
        return new ObjectOptimisticLockingFailureException(WebhookSubscription.class, id);
    }
}
