package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ParcelSearch;
import com.swiftdrop.delivery.ParcelSort;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.TrackingNumberGenerator;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Turns a {@link ParcelSearch} into a query on delivery.parcels and a sort order (DeliveryApi.search). */
final class ParcelSearchSpecification {

    private ParcelSearchSpecification() {
    }

    static Specification<Parcel> of(ParcelSearch search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!search.statuses().isEmpty()) {
                predicates.add(root.get("status").in(search.statuses()));
            }
            if (!search.pickupDistricts().isEmpty()) {
                predicates.add(root.get("pickup").get("district").in(search.pickupDistricts()));
            }
            if (!search.deliveryDistricts().isEmpty()) {
                predicates.add(root.get("recipient").get("district").in(search.deliveryDistricts()));
            }
            if (search.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), search.createdFrom()));
            }
            if (search.createdBefore() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), search.createdBefore()));
            }
            if (search.cashOnDelivery() != null) {
                predicates.add(search.cashOnDelivery()
                        ? cb.isNotNull(root.get("codAmount"))
                        : cb.isNull(root.get("codAmount")));
            }
            String text = search.text() == null ? "" : search.text().trim();
            if (!text.isEmpty()) {
                predicates.add(textMatches(text, root, cb));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static Sort sort(ParcelSort sort) {
        Sort.Order newest = Sort.Order.desc("createdAt");
        Sort.Order id = Sort.Order.desc("id");
        return switch (sort == null ? ParcelSort.NEWEST : sort) {
            case NEWEST -> Sort.by(newest, id);
            case OLDEST -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
            case FEE_HIGH -> Sort.by(Sort.Order.desc("feeAmount"), newest, id);
            case FEE_LOW -> Sort.by(Sort.Order.asc("feeAmount"), newest, id);
        };
    }

    /** A full parcel id matches only that parcel; anything else is searched in the number, names, phones and ref. */
    private static Predicate textMatches(String text, Root<Parcel> root, CriteriaBuilder cb) {
        Optional<UUID> id = parseUuid(text);
        if (id.isPresent()) {
            return cb.equal(root.get("id"), id.get());
        }
        String lower = like(text.toLowerCase(Locale.ROOT));
        String tracking = like(TrackingNumberGenerator.normalise(text));
        List<Predicate> any = new ArrayList<>(List.of(
                cb.like(root.get("trackingNumber"), tracking, '\\'),
                lowerLike(root.get("pickup").get("name"), lower, cb),
                lowerLike(root.get("recipient").get("name"), lower, cb),
                lowerLike(root.get("merchantOrderRef"), lower, cb),
                cb.like(root.get("pickup").get("phone"), like(text), '\\'),
                cb.like(root.get("recipient").get("phone"), like(text), '\\')));
        PhoneNumberNormalizer.normalize(text).ifPresent(phone -> {
            any.add(cb.equal(root.get("pickup").get("phone"), phone));
            any.add(cb.equal(root.get("recipient").get("phone"), phone));
        });
        return cb.or(any.toArray(Predicate[]::new));
    }

    private static Predicate lowerLike(Path<String> path, String pattern, CriteriaBuilder cb) {
        return cb.like(cb.lower(path), pattern, '\\');
    }

    /** "%text%" with the LIKE wildcards in the text escaped. */
    private static String like(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
