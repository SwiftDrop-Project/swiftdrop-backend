package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The body merchants receive must match API.md exactly and never carry personal data. */
class WebhookPayloadFactoryTest {

    private static final UUID EVENT = UUID.fromString("6f1c2a8e-4b0d-4c1e-9f3a-2d7b8e5c1a90");
    private static final UUID PARCEL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MERCHANT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String NOTE = "Leave at gate 7, call 0771234567";
    private static final Instant OCCURRED = Instant.parse("2026-11-13T09:15:00Z");

    private final WebhookPayloadFactory factory = new WebhookPayloadFactory(JsonMapper.builder().build());

    @Test
    void matchesTheApiMdExampleExactly() {
        String json = factory.build(event(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, null, null),
                "TECH-ORD-1001");

        assertThat(json).isEqualTo("{\"id\":\"6f1c2a8e-4b0d-4c1e-9f3a-2d7b8e5c1a90\",\"type\":\"parcel.delivered\","
                + "\"createdAt\":\"2026-11-13T09:15:00Z\",\"data\":{"
                + "\"parcelId\":\"22222222-2222-2222-2222-222222222222\",\"trackingNumber\":\"SD2611004817\","
                + "\"merchantOrderRef\":\"TECH-ORD-1001\",\"status\":\"DELIVERED\","
                + "\"previousStatus\":\"OUT_FOR_DELIVERY\",\"sequence\":7,\"branch\":null,\"failureReason\":null}}");
    }

    @Test
    void neverCarriesPersonalDataOrInternalIds() {
        String json = factory.build(event(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, null, null), null);

        assertThat(json)
                .doesNotContain(NOTE).doesNotContain("note")
                .doesNotContain(CUSTOMER.toString()).doesNotContain("customerId")
                .doesNotContain(ACTOR.toString()).doesNotContain("actorId").doesNotContain("actorRole")
                .doesNotContain("COURIER")
                .doesNotContain(MERCHANT.toString());
    }

    @Test
    void aNewParcelHasNoPreviousStatus() {
        String json = factory.build(event(null, ParcelStatus.CREATED, null, null), "ORD-1");

        assertThat(json).contains("\"type\":\"parcel.created\"").contains("\"previousStatus\":null");
    }

    @Test
    void branchAndFailureReasonAreSetForTheirEvents() {
        assertThat(factory.build(event(ParcelStatus.IN_TRANSIT, ParcelStatus.AT_BRANCH, District.KANDY, null), null))
                .contains("\"type\":\"parcel.at_branch\"").contains("\"branch\":\"KANDY\"");
        assertThat(factory.build(event(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, null,
                "RECIPIENT_UNAVAILABLE"), null))
                .contains("\"type\":\"parcel.failed\"").contains("\"failureReason\":\"RECIPIENT_UNAVAILABLE\"");
    }

    @Test
    void aMissingOrderReferenceIsNullButTheKeyStays() {
        assertThat(factory.build(event(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, null, null), null))
                .contains("\"merchantOrderRef\":null");
    }

    @Test
    void aPingCarriesOnlyTheSubscriptionId() {
        UUID subscription = UUID.fromString("77777777-7777-7777-7777-777777777777");

        assertThat(factory.ping(EVENT, subscription, OCCURRED)).isEqualTo(
                "{\"id\":\"6f1c2a8e-4b0d-4c1e-9f3a-2d7b8e5c1a90\",\"type\":\"webhook.ping\","
                        + "\"createdAt\":\"2026-11-13T09:15:00Z\","
                        + "\"data\":{\"subscriptionId\":\"77777777-7777-7777-7777-777777777777\"}}");
    }

    private static ParcelStatusChanged event(ParcelStatus from, ParcelStatus to, District branch, String failure) {
        return new ParcelStatusChanged(EVENT, PARCEL, 7, "SD2611004817", CUSTOMER, MERCHANT, from, to, ACTOR,
                "COURIER", NOTE, failure, OCCURRED, branch);
    }
}
