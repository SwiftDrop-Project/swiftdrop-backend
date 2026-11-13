package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.internal.domain.AssignmentItem;
import com.swiftdrop.dispatch.internal.domain.CourierAssignment;
import com.swiftdrop.dispatch.internal.persistence.AssignmentItemRepository;
import com.swiftdrop.dispatch.internal.persistence.CourierAssignmentRepository;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The courier-facing {@link AssignmentApi} (used by the courier module). Every method is scoped to one courier.
 * Writes join the caller's transaction ({@code REQUIRED}), so the courier module's parcel status change and the
 * outcome recorded here commit or roll back together.
 */
@Service
@Transactional(readOnly = true)
public class AssignmentService implements AssignmentApi {

    /** The largest page {@link #findClosedForCourier} returns. */
    static final int MAX_PAGE_SIZE = 100;

    private final CourierAssignmentRepository assignments;
    private final AssignmentItemRepository items;
    private final Clock clock;

    @Autowired
    public AssignmentService(CourierAssignmentRepository assignments, AssignmentItemRepository items) {
        this(assignments, items, Clock.systemUTC());
    }

    AssignmentService(CourierAssignmentRepository assignments, AssignmentItemRepository items, Clock clock) {
        this.assignments = assignments;
        this.items = items;
        this.clock = clock;
    }

    @Override
    public List<AssignmentView> findOpenForCourier(UUID courierId, Instant closedSince) {
        Objects.requireNonNull(courierId, "courierId");
        Objects.requireNonNull(closedSince, "closedSince");
        return assignments.findOpenForCourier(courierId, closedSince).stream()
                .map(CourierAssignment::toView)
                .toList();
    }

    @Override
    public Page<AssignmentView> findClosedForCourier(UUID courierId, UUID parcelId, Pageable pageable) {
        Objects.requireNonNull(courierId, "courierId");
        // The queries carry their own order, so only the page number and a capped size are passed on.
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
        Page<CourierAssignment> found = parcelId == null
                ? assignments.findClosedForCourier(courierId, page)
                : assignments.findClosedForCourierAndParcel(courierId, parcelId, page);
        return found.map(CourierAssignment::toView);
    }

    @Override
    public Optional<AssignmentView> findByIdForCourier(UUID assignmentId, UUID courierId) {
        return assignments.findByIdAndCourierId(assignmentId, courierId).map(CourierAssignment::toView);
    }

    @Override
    public List<AssignmentItemView> findItemsForCourierAndParcel(UUID courierId, UUID parcelId) {
        return items.findForCourierAndParcel(courierId, parcelId).stream()
                .map(AssignmentItem::toView)
                .toList();
    }

    @Override
    @Transactional
    public AssignmentView recordOutcome(RecordOutcomeCommand command) {
        CourierAssignment assignment = assignments.findByIdAndCourierId(command.assignmentId(), command.courierId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        assignment.recordOutcome(command, clock.instant());
        return save(assignment).toView();
    }

    @Override
    @Transactional
    public AssignmentView complete(UUID assignmentId, UUID courierId) {
        CourierAssignment assignment = assignments.findByIdAndCourierId(assignmentId, courierId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        assignment.complete(clock.instant());
        return save(assignment).toView();
    }

    /** Writes now, so a change that raced with another one fails here with a clear 409. */
    private CourierAssignment save(CourierAssignment assignment) {
        try {
            return assignments.saveAndFlush(assignment);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConflictException(ErrorCode.ASSIGNMENT_CHANGED);
        }
    }
}
