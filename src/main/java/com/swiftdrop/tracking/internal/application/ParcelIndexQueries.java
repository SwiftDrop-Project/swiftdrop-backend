package com.swiftdrop.tracking.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.tracking.internal.domain.ParcelIndexEntry;
import com.swiftdrop.tracking.internal.dto.IndexedParcelResponse;
import com.swiftdrop.tracking.internal.dto.ParcelStatusCountsResponse;
import com.swiftdrop.tracking.internal.dto.TrackingPlace;
import com.swiftdrop.tracking.internal.persistence.ParcelIndexRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's all-parcels tracking list: filters, paging and counts per status over tracking.parcel_index. */
@Service
public class ParcelIndexQueries {

    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    /**
     * @param search   part of a tracking number; spaces and case ignored
     * @param district matches the sender's or recipient's district, or the branch the parcel is at
     * @param from     updated on or after this day (Sri Lankan time)
     * @param to       updated on or before this day (Sri Lankan time)
     */
    public record Filter(String search, ParcelStatus status, District district, LocalDate from, LocalDate to) {
    }

    private final ParcelIndexRepository index;
    private final EntityManager entityManager;

    public ParcelIndexQueries(ParcelIndexRepository index, EntityManager entityManager) {
        this.index = index;
        this.entityManager = entityManager;
    }

    /** Most recently updated first. */
    @Transactional(readOnly = true)
    public PageResponse<IndexedParcelResponse> search(Filter filter, int page, int size) {
        Specification<ParcelIndexEntry> spec = (root, query, cb) -> cb.and(predicates(filter, true, root, cb));
        var sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("parcelId"));
        return PageResponse.from(index.findAll(spec, PageRequest.of(page, size, sort)), ParcelIndexQueries::toResponse);
    }

    /** Counts per status for the filter, ignoring its status (so the status filter can show them). */
    @Transactional(readOnly = true)
    public ParcelStatusCountsResponse counts(Filter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<ParcelIndexEntry> root = query.from(ParcelIndexEntry.class);
        query.multiselect(root.get("status"), cb.count(root))
                .where(predicates(filter, false, root, cb))
                .groupBy(root.get("status"));
        Map<ParcelStatus, Long> byStatus = new EnumMap<>(ParcelStatus.class);
        for (ParcelStatus status : ParcelStatus.values()) {
            byStatus.put(status, 0L);
        }
        long total = 0;
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            long count = row.get(1, Long.class);
            byStatus.put(row.get(0, ParcelStatus.class), count);
            total += count;
        }
        return new ParcelStatusCountsResponse(total, byStatus);
    }

    private static Predicate[] predicates(Filter filter, boolean withStatus, Root<ParcelIndexEntry> root,
                                          CriteriaBuilder cb) {
        List<Predicate> where = new ArrayList<>();
        String search = filter.search() == null ? "" : filter.search().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!search.isEmpty()) {
            String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.add(cb.like(root.get("trackingNumber"), "%" + escaped + "%", '\\'));
        }
        if (withStatus && filter.status() != null) {
            where.add(cb.equal(root.get("status"), filter.status()));
        }
        if (filter.district() != null) {
            where.add(cb.or(
                    cb.equal(root.get("originDistrict"), filter.district()),
                    cb.equal(root.get("destinationDistrict"), filter.district()),
                    cb.equal(root.get("currentBranch"), filter.district())));
        }
        if (filter.from() != null) {
            where.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), filter.from().atStartOfDay(ZONE).toInstant()));
        }
        if (filter.to() != null) {
            where.add(cb.lessThan(root.get("updatedAt"), filter.to().plusDays(1).atStartOfDay(ZONE).toInstant()));
        }
        return where.toArray(Predicate[]::new);
    }

    private static IndexedParcelResponse toResponse(ParcelIndexEntry entry) {
        return new IndexedParcelResponse(
                entry.getParcelId(),
                entry.getTrackingNumber(),
                entry.getStatus(),
                entry.getOriginDistrict() == null ? null
                        : new TrackingPlace(entry.getOriginCity(), entry.getOriginDistrict()),
                entry.getDestinationDistrict() == null ? null
                        : new TrackingPlace(entry.getDestinationCity(), entry.getDestinationDistrict()),
                entry.getCurrentBranch(),
                entry.getFailureReason(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getParcelVersion());
    }
}
