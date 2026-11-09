package com.swiftdrop.dispatch.internal.persistence;

import com.swiftdrop.dispatch.internal.domain.AssignmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
