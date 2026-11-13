package com.swiftdrop.dispatch;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the courier module (WP9) may ask dispatch about a courier's assignments: read them, record what happened to
 * each parcel, and complete them. Dispatcher operations (create, add or remove items, cancel, find by branch) are
 * WP8's to design and are not part of this contract yet.
 *
 * <p>Every method is scoped to one courier: an assignment, item or parcel that is not theirs is treated as unknown,
 * and the caller answers 404. Writes join the caller's transaction, so the courier module can change the parcel
 * status through {@code DeliveryApi} in the same transaction and both roll back together.
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier). Implementation pending: until it is merged, the
 * courier module answers 503 SERVICE_UNAVAILABLE.
 */
public interface AssignmentApi {

    /**
     * The courier's open assignments (ASSIGNED, IN_PROGRESS), plus the closed ones (COMPLETED, CANCELLED) that were
     * closed at or after {@code closedSince}. Any order.
     */
    List<AssignmentView> findOpenForCourier(UUID courierId, Instant closedSince);

    /**
     * The courier's closed assignments (COMPLETED, CANCELLED), newest close first; the page size is capped at 100.
     *
     * @param parcelId optional; when not null, only assignments that held this parcel. The caller resolves a tracking
     *                 number to a parcel id through {@code DeliveryApi}
     */
    Page<AssignmentView> findClosedForCourier(UUID courierId, UUID parcelId, Pageable pageable);

    /** The assignment, if it belongs to {@code courierId}; empty otherwise (the caller answers 404). */
    Optional<AssignmentView> findByIdForCourier(UUID assignmentId, UUID courierId);

    /**
     * The items for this parcel in any of the courier's assignments, newest assignment first. Empty when the courier
     * never had the parcel (the caller answers 404).
     */
    List<AssignmentItemView> findItemsForCourierAndParcel(UUID courierId, UUID parcelId);

    /**
     * Records one outcome for one item. The first outcome moves the assignment from ASSIGNED to IN_PROGRESS. A
     * closing outcome ({@link ItemOutcome#closes}) sets the item's {@code closedAt}.
     *
     * @return the assignment after the change
     * @throws com.swiftdrop.shared.error.NotFoundException     NOT_FOUND when the assignment is not the courier's, or
     *                                                          the parcel is not in it
     * @throws com.swiftdrop.shared.error.BusinessRuleException ASSIGNMENT_CLOSED when the assignment is COMPLETED or
     *                                                          CANCELLED; TASK_STEP_NOT_ALLOWED when
     *                                                          {@link ItemOutcome#canMoveTo} is false for the item
     */
    AssignmentView recordOutcome(RecordOutcomeCommand command);

    /**
     * Completes the assignment. Allowed only when every item is closed, even if some parcels were never taken
     * (NOT_COLLECTED, NOT_LOADED). Completing it does not change any parcel status.
     *
     * @return the assignment after the change
     * @throws com.swiftdrop.shared.error.NotFoundException     NOT_FOUND when the assignment is not the courier's
     * @throws com.swiftdrop.shared.error.BusinessRuleException ASSIGNMENT_CLOSED when it is already COMPLETED or
     *                                                          CANCELLED; ASSIGNMENT_NOT_FINISHED when an item is
     *                                                          still open
     */
    AssignmentView complete(UUID assignmentId, UUID courierId);
}
