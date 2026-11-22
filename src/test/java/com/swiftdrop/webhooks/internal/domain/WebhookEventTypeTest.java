package com.swiftdrop.webhooks.internal.domain;

import com.swiftdrop.delivery.ParcelStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The event types match API.md "Webhooks" and the CHECK constraints of the webhooks migration. */
class WebhookEventTypeTest {

    @ParameterizedTest
    @EnumSource(ParcelStatus.class)
    void everyParcelStatusHasAnEvent(ParcelStatus status) {
        // fails when delivery adds a status: add an event for it here, in API.md and in a new migration
        WebhookEventType type = WebhookEventType.fromStatus(status);

        assertThat(type.subscribable()).isTrue();
        assertThat(type.wireName()).isEqualTo("parcel." + status.name().toLowerCase());
    }

    @Test
    void wireNamesMatchApiMdAndTheMigration() {
        assertThat(Arrays.stream(WebhookEventType.values()).map(WebhookEventType::wireName)).containsExactly(
                "parcel.created", "parcel.picked_up", "parcel.at_branch", "parcel.in_transit",
                "parcel.out_for_delivery", "parcel.delivered", "parcel.failed", "webhook.ping");
    }

    @ParameterizedTest
    @EnumSource(WebhookEventType.class)
    void wireNamesRoundTrip(WebhookEventType type) {
        assertThat(WebhookEventType.fromWireName(type.wireName())).contains(type);
    }

    @Test
    void unknownWireNamesAreEmpty() {
        assertThat(WebhookEventType.fromWireName("parcel.lost")).isEmpty();
        assertThat(WebhookEventType.fromWireName("PARCEL_DELIVERED")).isEmpty();
    }

    @Test
    void onlyThePingCantBeSubscribedTo() {
        assertThat(Arrays.stream(WebhookEventType.values()).filter(t -> !t.subscribable()))
                .containsExactly(WebhookEventType.WEBHOOK_PING);
    }

    @Test
    void aSubscriptionRejectsThePingAndAnEmptySet() {
        UUID merchant = UUID.randomUUID();
        assertThatThrownBy(() -> WebhookSubscription.create(merchant, "https://shop.example.com/hook", "x",
                Set.of(WebhookEventType.WEBHOOK_PING), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookSubscription.create(merchant, "https://shop.example.com/hook", "x",
                Set.of(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSubscriptionToStringHasNoSecretOrUrl() {
        WebhookSubscription subscription = WebhookSubscription.create(UUID.randomUUID(),
                "https://shop.example.com/hook", "ciphertext-value", Set.of(WebhookEventType.PARCEL_DELIVERED),
                java.time.Instant.now());

        assertThat(subscription.toString()).doesNotContain("ciphertext-value").doesNotContain("shop.example.com");
    }
}
