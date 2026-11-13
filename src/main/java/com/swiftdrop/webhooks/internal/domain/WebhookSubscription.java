package com.swiftdrop.webhooks.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One merchant URL that receives webhooks (webhooks.subscriptions). Never deleted: turning it off sets
 * {@code active = false}. The signing secret is stored only encrypted; it is never in {@link #toString()}.
 */
@Entity
@Table(name = "subscriptions", schema = "webhooks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookSubscription {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 2048)
    private String url;

    /** AES-GCM ciphertext of the signing secret. Never log it. */
    @Column(name = "secret_ciphertext", nullable = false)
    private String secretCiphertext;

    /** Wire names (e.g. parcel.delivered); use {@link #eventTypes()} instead. */
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private String[] eventTypes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Null until first saved, so Spring Data inserts instead of merging. */
    @Version
    private Long version;

    /** A new, active subscription. {@code eventTypes} must be non-empty parcel events (not webhook.ping). */
    public static WebhookSubscription create(UUID merchantId, String url, String secretCiphertext,
                                             Set<WebhookEventType> eventTypes, Instant now) {
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.id = UUID.randomUUID();
        subscription.merchantId = merchantId;
        subscription.url = url;
        subscription.secretCiphertext = secretCiphertext;
        subscription.eventTypes = toWireNames(eventTypes);
        subscription.active = true;
        subscription.createdAt = now;
        subscription.updatedAt = now;
        return subscription;
    }

    /** The event types this subscription receives. */
    public Set<WebhookEventType> eventTypes() {
        EnumSet<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        for (String wireName : eventTypes) {
            types.add(WebhookEventType.fromWireName(wireName)
                    .orElseThrow(() -> new IllegalStateException("Unknown webhook event type in database: " + wireName)));
        }
        return Collections.unmodifiableSet(types);
    }

    /** Turns the subscription off (DELETE /merchant/webhooks/{id}). Turning it off again changes nothing. */
    public void deactivate(Instant now) {
        if (active) {
            active = false;
            updatedAt = now;
        }
    }

    /** True if this subscription is active and receives {@code type}. */
    public boolean listensTo(WebhookEventType type) {
        return active && Arrays.asList(eventTypes).contains(type.wireName());
    }

    private static String[] toWireNames(Set<WebhookEventType> types) {
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("A subscription needs at least one event type");
        }
        // EnumSet keeps the declaration order, so the stored array is always in the same order
        EnumSet<WebhookEventType> sorted = EnumSet.copyOf(types);
        String[] wireNames = new String[sorted.size()];
        int i = 0;
        for (WebhookEventType type : sorted) {
            if (!type.subscribable()) {
                throw new IllegalArgumentException(type.wireName() + " can't be subscribed to");
            }
            wireNames[i++] = type.wireName();
        }
        return wireNames;
    }

    /** Leaves out the URL and the secret. */
    @Override
    public String toString() {
        return "WebhookSubscription[id=" + id + ", merchantId=" + merchantId + ", active=" + active + "]";
    }
}
