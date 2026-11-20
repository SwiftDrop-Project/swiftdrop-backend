package com.swiftdrop.tracking.internal.application;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.tracking.ParcelBreakdown;
import com.swiftdrop.tracking.ParcelTracking;
import com.swiftdrop.tracking.TrackingApi;
import com.swiftdrop.tracking.internal.dto.IndexedParcelResponse;
import com.swiftdrop.tracking.internal.dto.TrackingPlace;
import com.swiftdrop.tracking.internal.dto.TrackingResponse;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TrackingApi} on top of the public lookup, the user's own parcels list and the parcel index. */
@Service
class TrackingQueries implements TrackingApi {

    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    private final TrackingService tracking;
    private final TrackingRateLimiter rateLimiter;
    private final MyParcels myParcels;
    private final EntityManager entityManager;

    TrackingQueries(TrackingService tracking, TrackingRateLimiter rateLimiter, MyParcels myParcels,
                    EntityManager entityManager) {
        this.tracking = tracking;
        this.rateLimiter = rateLimiter;
        this.myParcels = myParcels;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ParcelTracking> find(String trackingNumber, String clientKey) {
        rateLimiter.check(clientKey);
        try {
            return Optional.of(toTracking(tracking.track(trackingNumber)));
        } catch (NotFoundException unknown) {
            return Optional.empty();
        }
    }

    @Override
    public List<ParcelTracking> mine(Owner owner, UUID userId, ParcelStatus status, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be 1-100");
        }
        MyParcels.Owner which = owner == Owner.MERCHANT ? MyParcels.Owner.MERCHANT : MyParcels.Owner.CUSTOMER;
        return myParcels.page(which, userId, status, 0, limit).content().stream()
                .map(TrackingQueries::toTracking)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelBreakdown breakdown(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("from must be before to, at most 366 days apart");
        }
        Instant start = from.atStartOfDay(SRI_LANKA).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SRI_LANKA).toInstant();
        return new ParcelBreakdown(from, to,
                count("originDistrict", "p.createdAt >= :start and p.createdAt < :end", start, end, District.class),
                count("destinationDistrict", "p.createdAt >= :start and p.createdAt < :end", start, end,
                        District.class),
                count("failureReason", "p.status = com.swiftdrop.delivery.ParcelStatus.FAILED"
                        + " and p.updatedAt >= :start and p.updatedAt < :end", start, end, FailureReason.class));
    }

    /** Counts per value of {@code field} (nulls left out), largest first. */
    private <K> Map<K, Long> count(String field, String where, Instant start, Instant end, Class<K> type) {
        List<Object[]> rows = entityManager.createQuery(
                        "select p." + field + ", count(p) from ParcelIndexEntry p where " + where
                                + " and p." + field + " is not null group by p." + field
                                + " order by count(p) desc, p." + field, Object[].class)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        Map<K, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(type.cast(row[0]), (Long) row[1]);
        }
        return counts;
    }

    private static ParcelTracking toTracking(TrackingResponse parcel) {
        return new ParcelTracking(parcel.trackingNumber(), parcel.status(), parcel.currentBranch(),
                place(parcel.from()), place(parcel.to()), parcel.createdAt(), parcel.updatedAt(),
                parcel.history().stream()
                        .map(step -> new ParcelTracking.Step(step.status(), step.branch(), step.failureReason(),
                                step.occurredAt()))
                        .toList());
    }

    private static ParcelTracking toTracking(IndexedParcelResponse parcel) {
        return new ParcelTracking(parcel.trackingNumber(), parcel.status(), parcel.currentBranch(),
                place(parcel.from()), place(parcel.to()), parcel.createdAt(), parcel.updatedAt(), List.of());
    }

    private static ParcelTracking.Place place(TrackingPlace place) {
        return place == null ? null : new ParcelTracking.Place(place.city(), place.district());
    }
}
