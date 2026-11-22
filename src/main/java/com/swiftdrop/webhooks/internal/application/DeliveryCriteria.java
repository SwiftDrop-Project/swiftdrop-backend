package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Checked filters of a delivery list and the query they make (API.md "Webhooks", "Search").
 *
 * <p>Search: a UUID matches the delivery id, the event id or the parcel id exactly. Anything else matches the
 * tracking number exactly after removing spaces and upper-casing ({@code sd 2611 0048 17} finds
 * {@code SD2611004817}), or the merchant's order number containing the term, ignoring case ({@code % _ \} in the
 * term are literal). Test pings have no parcel, so only an id search finds them.
 *
 * <p>Dates are calendar days in Sri Lankan time, both included: {@code from} means createdAt at or after the start
 * of that day, {@code to} means before the start of the next day.
 */
final class DeliveryCriteria {

    static final String EVENT_TYPE_FIELD = "eventType";
    static final String SEARCH_FIELD = "search";
    static final String FROM_FIELD = "from";
    static final String TO_FIELD = "to";
    static final int MAX_SEARCH_LENGTH = 64;
    static final String SEARCH_TOO_LONG = "Search with at most " + MAX_SEARCH_LENGTH + " characters.";
    static final String BAD_DATE = "Enter a date like 2026-11-15.";
    static final String TO_BEFORE_FROM = "The end date can't be before the start date.";
    /** The merchants' time zone: "15 Nov" means 15 Nov in Sri Lanka. */
    static final ZoneId MERCHANT_ZONE = ZoneId.of("Asia/Colombo");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final char LIKE_ESCAPE = '\\';

    private final DeliveryStatus status;
    private final WebhookEventType eventType;
    private final UUID searchId;
    private final String searchTrackingNumber;
    private final String searchOrderRefPattern;
    private final Instant fromInclusive;
    private final Instant toExclusive;

    private DeliveryCriteria(DeliveryStatus status, WebhookEventType eventType, UUID searchId,
                             String searchTrackingNumber, String searchOrderRefPattern, Instant fromInclusive,
                             Instant toExclusive) {
        this.status = status;
        this.eventType = eventType;
        this.searchId = searchId;
        this.searchTrackingNumber = searchTrackingNumber;
        this.searchOrderRefPattern = searchOrderRefPattern;
        this.fromInclusive = fromInclusive;
        this.toExclusive = toExclusive;
    }

    /** Checks the filters: 400 with {@code fieldErrors} for an unknown event type, a too long search or a bad date. */
    static DeliveryCriteria parse(DeliveryFilters filters) {
        WebhookEventType type = parseEventType(filters.eventType());

        UUID searchId = null;
        String trackingNumber = null;
        String orderRefPattern = null;
        String term = filters.search() == null ? "" : filters.search().trim();
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new FieldValidationException(SEARCH_FIELD, SEARCH_TOO_LONG);
        }
        if (!term.isEmpty()) {
            if (UUID_PATTERN.matcher(term).matches()) {
                searchId = UUID.fromString(term);
            } else {
                trackingNumber = term.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                orderRefPattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
            }
        }

        LocalDate from = parseDate(filters.from(), FROM_FIELD);
        LocalDate to = parseDate(filters.to(), TO_FIELD);
        if (from != null && to != null && to.isBefore(from)) {
            throw new FieldValidationException(TO_FIELD, TO_BEFORE_FROM);
        }
        Instant fromInclusive = from == null ? null : from.atStartOfDay(MERCHANT_ZONE).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(MERCHANT_ZONE).toInstant();

        return new DeliveryCriteria(filters.status(), type, searchId, trackingNumber, orderRefPattern, fromInclusive,
                toExclusive);
    }

    /**
     * The query: one subscription (merchant history) or one optional merchant (admin list), AND every filter that is
     * set. The search alternatives are OR-ed inside their own group.
     */
    Specification<WebhookDelivery> toSpecification(UUID subscriptionId, UUID merchantId) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (subscriptionId != null) {
                conditions.add(cb.equal(root.get("subscriptionId"), subscriptionId));
            }
            if (merchantId != null) {
                conditions.add(cb.equal(root.get("merchantId"), merchantId));
            }
            if (status != null) {
                conditions.add(cb.equal(root.get("status"), status));
            }
            if (eventType != null) {
                conditions.add(cb.equal(root.get("eventType"), eventType.wireName())); // stored as the wire name
            }
            if (searchId != null) {
                conditions.add(cb.or(
                        cb.equal(root.get("id"), searchId),
                        cb.equal(root.get("eventId"), searchId),
                        cb.equal(root.get("parcelId"), searchId)));
            } else if (searchTrackingNumber != null) {
                conditions.add(cb.or(
                        cb.equal(root.get("trackingNumber"), searchTrackingNumber),
                        cb.like(cb.lower(root.get("merchantOrderRef")), searchOrderRefPattern, LIKE_ESCAPE)));
            }
            if (fromInclusive != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive));
            }
            if (toExclusive != null) {
                conditions.add(cb.lessThan(root.get("createdAt"), toExclusive));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /**
     * A wire name such as {@code parcel.delivered} or {@code webhook.ping}. Null or blank means no filter; anything
     * else unknown is 400 with {@code fieldErrors.eventType}, worded like the {@code eventTypes} check on create.
     */
    static WebhookEventType parseEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        String wireName = eventType.trim();
        return WebhookEventType.fromWireName(wireName)
                .orElseThrow(() -> new FieldValidationException(EVENT_TYPE_FIELD,
                        "'" + wireName + "' isn't a webhook event"));
    }

    /** {@code 2026-11-15}; null or blank means no limit. The value itself is never echoed back. */
    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new FieldValidationException(field, BAD_DATE);
        }
    }

    /** Makes {@code \ % _} literal in a LIKE pattern (escape character: backslash). */
    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
