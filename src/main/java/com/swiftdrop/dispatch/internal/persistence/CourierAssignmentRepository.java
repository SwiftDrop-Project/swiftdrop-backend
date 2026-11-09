package com.swiftdrop.dispatch.internal.persistence;

import com.swiftdrop.dispatch.internal.domain.CourierAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Courier assignments. The items load in batches (@BatchSize on CourierAssignment.items), so list queries don't fetch
 * them one assignment at a time. Paged queries carry their own order: pass an unsorted Pageable.
 */
public interface CourierAssignmentRepository extends JpaRepository<CourierAssignment, UUID> {

    /** AssignmentApi.findByIdForCourier: empty when the assignment is someone else's. */
    Optional<CourierAssignment> findByIdAndCourierId(UUID id, UUID courierId);

    /**
     * AssignmentApi.findOpenForCourier: the courier's open assignments (ASSIGNED, IN_PROGRESS), plus the closed ones
     * that were closed at or after {@code closedSince}. Any order. Uses idx_courier_assignments_courier_status.
     */
    @Query("select a from CourierAssignment a where a.courierId = :courierId"
            + " and (a.status in (com.swiftdrop.dispatch.AssignmentStatus.ASSIGNED,"
            + " com.swiftdrop.dispatch.AssignmentStatus.IN_PROGRESS)"
            + " or coalesce(a.completedAt, a.cancelledAt) >= :closedSince)")
    List<CourierAssignment> findOpenForCourier(UUID courierId, Instant closedSince);

    /**
     * AssignmentApi.findClosedForCourier without a parcel: COMPLETED and CANCELLED, newest close first. The id breaks
     * ties so paging is stable. Uses idx_courier_assignments_courier_closed.
     */
    @Query(value = "select a from CourierAssignment a where a.courierId = :courierId"
            + " and a.status in (com.swiftdrop.dispatch.AssignmentStatus.COMPLETED,"
            + " com.swiftdrop.dispatch.AssignmentStatus.CANCELLED)"
            + " order by coalesce(a.completedAt, a.cancelledAt) desc, a.id desc",
            countQuery = "select count(a) from CourierAssignment a where a.courierId = :courierId"
                    + " and a.status in (com.swiftdrop.dispatch.AssignmentStatus.COMPLETED,"
                    + " com.swiftdrop.dispatch.AssignmentStatus.CANCELLED)")
    Page<CourierAssignment> findClosedForCourier(UUID courierId, Pageable pageable);

    /** The same, only the assignments that held this parcel. */
    @Query(value = "select a from CourierAssignment a where a.courierId = :courierId"
            + " and a.status in (com.swiftdrop.dispatch.AssignmentStatus.COMPLETED,"
            + " com.swiftdrop.dispatch.AssignmentStatus.CANCELLED)"
            + " and exists (select i from AssignmentItem i where i.assignment = a and i.parcelId = :parcelId)"
            + " order by coalesce(a.completedAt, a.cancelledAt) desc, a.id desc",
            countQuery = "select count(a) from CourierAssignment a where a.courierId = :courierId"
                    + " and a.status in (com.swiftdrop.dispatch.AssignmentStatus.COMPLETED,"
                    + " com.swiftdrop.dispatch.AssignmentStatus.CANCELLED)"
                    + " and exists (select i from AssignmentItem i where i.assignment = a"
                    + " and i.parcelId = :parcelId)")
    Page<CourierAssignment> findClosedForCourierAndParcel(UUID courierId, UUID parcelId, Pageable pageable);
}
