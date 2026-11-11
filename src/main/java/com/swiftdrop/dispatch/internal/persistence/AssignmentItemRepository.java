package com.swiftdrop.dispatch.internal.persistence;

import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.internal.domain.AssignmentItem;
import com.swiftdrop.shared.reference.District;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Assignment items, for reads across assignments. Items are created and changed only through their
 * CourierAssignment (cascade), never saved here directly.
 */
public interface AssignmentItemRepository extends JpaRepository<AssignmentItem, UUID> {

    /**
     * AssignmentApi.findItemsForCourierAndParcel: the items for this parcel in the courier's assignments, newest
     * assignment first. Uses idx_assignment_items_parcel.
     */
    @Query("select i from AssignmentItem i join fetch i.assignment a"
            + " where i.parcelId = :parcelId and a.courierId = :courierId order by a.createdAt desc, a.id desc")
    List<AssignmentItem> findForCourierAndParcel(UUID courierId, UUID parcelId);

    /** Whether the parcel is open in an assignment (uk_assignment_items_open_parcel allows at most one). */
    boolean existsByParcelIdAndClosedAtIsNull(UUID parcelId);

    /** The parcels among these that are open in an assignment, so the dispatcher's queue can leave them out. */
    @Query("select i.parcelId from AssignmentItem i where i.closedAt is null and i.parcelId in :parcelIds")
    Set<UUID> findOpenParcelIds(Collection<UUID> parcelIds);

    /**
     * Items whose outcome is one of {@code outcomes} and changed at or after {@code since}, in assignments that start
     * or end at the branch, newest change first, with their assignment. For the dispatcher's delivery issues.
     */
    @Query("select i from AssignmentItem i join fetch i.assignment a where i.outcome in :outcomes"
            + " and i.updatedAt >= :since and (a.originBranch = :branch or a.destinationBranch = :branch)"
            + " order by i.updatedAt desc, i.id desc")
    List<AssignmentItem> findIssues(Collection<ItemOutcome> outcomes, District branch, Instant since,
                                    Pageable pageable);

    /** How many such items there are (the overview's count). */
    @Query("select count(i) from AssignmentItem i join i.assignment a where i.outcome in :outcomes"
            + " and i.updatedAt >= :since and (a.originBranch = :branch or a.destinationBranch = :branch)")
    long countIssues(Collection<ItemOutcome> outcomes, District branch, Instant since);
}
