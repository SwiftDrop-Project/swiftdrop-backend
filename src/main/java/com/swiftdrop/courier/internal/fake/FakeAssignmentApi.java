// TEMPORARY - delete when WP8 implements AssignmentApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY in-memory {@link AssignmentApi} for courier demos, on only with {@code SWIFTDROP_FAKE_DISPATCH=true}.
 *
 * <p>Follows the {@code AssignmentApi} Javadoc, so behaviour stays the same when WP8's implementation replaces it.
 * Nothing is written to the database and no events are published. Changes made inside a transaction are undone when
 * it rolls back ({@link FakeTransactions}), like the real tables would be.
 *
 * <p>{@code @Primary}: if a real implementation exists while the flag is still on, this one wins instead of the app
 * failing on two beans, and {@link DemoCourierSeeder} logs a warning.
 */
@Component
@Primary
@ConditionalOnProperty(name = "swiftdrop.dev.fake-dispatch", havingValue = "true")
class FakeAssignmentApi implements AssignmentApi {

    /** Each view is immutable and replaced on change, so reads need no lock. */
    private final Map<UUID, AssignmentView> assignments = new ConcurrentHashMap<>();
    private final Clock clock;

    FakeAssignmentApi() {
        this(Clock.systemUTC());
    }

    FakeAssignmentApi(Clock clock) {
        this.clock = clock;
    }

    /** Adds an assignment. Used by the seeder and tests. */
    void put(AssignmentView assignment) {
        assignments.put(assignment.id(), assignment);
    }

    boolean isEmpty() {
        return assignments.isEmpty();
    }

    /** Every assignment, of every courier. For tests. */
    List<AssignmentView> all() {
        return List.copyOf(assignments.values());
    }

    // ---- Reads ----

    @Override
    public List<AssignmentView> findOpenForCourier(UUID courierId, Instant closedSince) {
        return assignments.values().stream()
                .filter(assignment -> courierId.equals(assignment.courierId()))
                .filter(assignment -> !assignment.status().isClosed() || !closedAt(assignment).isBefore(closedSince))
                .toList();
    }

    @Override
    public Page<AssignmentView> findClosedForCourier(UUID courierId, UUID parcelId, Pageable pageable) {
        List<AssignmentView> closed = assignments.values().stream()
                .filter(assignment -> courierId.equals(assignment.courierId()))
                .filter(assignment -> assignment.status().isClosed())
                .filter(assignment -> parcelId == null || holds(assignment, parcelId))
                .sorted(Comparator.comparing(FakeAssignmentApi::closedAt).reversed())
                .toList();
        if (pageable.isUnpaged()) {
            return new PageImpl<>(closed, pageable, closed.size());
        }
        int from = (int) Math.min(pageable.getOffset(), closed.size());
        int to = Math.min(from + pageable.getPageSize(), closed.size());
        return new PageImpl<>(closed.subList(from, to), pageable, closed.size());
    }

    @Override
    public Optional<AssignmentView> findByIdForCourier(UUID assignmentId, UUID courierId) {
        return Optional.ofNullable(assignments.get(assignmentId))
                .filter(assignment -> courierId.equals(assignment.courierId()));
    }

    @Override
    public List<AssignmentItemView> findItemsForCourierAndParcel(UUID courierId, UUID parcelId) {
        return assignments.values().stream()
                .filter(assignment -> courierId.equals(assignment.courierId()))
                .sorted(Comparator.comparing(AssignmentView::assignedAt).reversed())
                .flatMap(assignment -> assignment.items().stream())
                .filter(item -> item.parcelId().equals(parcelId))
                .toList();
    }

    // ---- Writes ----

    @Override
    public synchronized AssignmentView recordOutcome(RecordOutcomeCommand command) {
        AssignmentView assignment = findByIdForCourier(command.assignmentId(), command.courierId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        AssignmentItemView item = assignment.items().stream()
                .filter(candidate -> candidate.parcelId().equals(command.parcelId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (assignment.status().isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
        ItemOutcome outcome = command.outcome();
        if (!item.outcome().canMoveTo(assignment.type(), outcome)) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }

        Instant now = clock.instant();
        AssignmentItemView updatedItem = new AssignmentItemView(item.assignmentId(), item.parcelId(), item.sequence(),
                outcome,
                command.problem(),
                // RETURNED keeps the reason of the failed delivery, for the record
                command.failureReason() != null ? command.failureReason() : keptReason(item, outcome),
                command.note() != null ? command.note() : item.note(),
                command.receivedBy() != null ? command.receivedBy() : item.receivedBy(),
                command.recipientName() != null ? command.recipientName() : item.recipientName(),
                now,
                outcome.closes(assignment.type()) ? now : null);
        List<AssignmentItemView> items = assignment.items().stream()
                .map(candidate -> candidate == item ? updatedItem : candidate)
                .toList();
        boolean starting = assignment.status() == AssignmentStatus.ASSIGNED;
        return replace(assignment, new AssignmentView(assignment.id(), assignment.courierId(), assignment.type(),
                starting ? AssignmentStatus.IN_PROGRESS : assignment.status(),
                assignment.originBranch(), assignment.destinationBranch(), assignment.scheduledFor(),
                assignment.note(), assignment.assignedAt(),
                starting ? now : assignment.startedAt(),
                assignment.completedAt(), assignment.cancelledAt(), items, assignment.version() + 1));
    }

    @Override
    public synchronized AssignmentView complete(UUID assignmentId, UUID courierId) {
        AssignmentView assignment = findByIdForCourier(assignmentId, courierId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (assignment.status().isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
        if (assignment.items().stream().anyMatch(item -> !item.isClosed())) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_NOT_FINISHED);
        }
        Instant now = clock.instant();
        return replace(assignment, new AssignmentView(assignment.id(), assignment.courierId(), assignment.type(),
                AssignmentStatus.COMPLETED, assignment.originBranch(), assignment.destinationBranch(),
                assignment.scheduledFor(), assignment.note(), assignment.assignedAt(),
                assignment.startedAt() != null ? assignment.startedAt() : now,
                now, null, assignment.items(), assignment.version() + 1));
    }

    // ---- Helpers ----

    private AssignmentView replace(AssignmentView before, AssignmentView after) {
        assignments.put(after.id(), after);
        FakeTransactions.onRollback(this, () -> restore(before));
        return after;
    }

    private synchronized void restore(AssignmentView before) {
        assignments.put(before.id(), before);
    }

    private static FailureReason keptReason(AssignmentItemView item, ItemOutcome outcome) {
        return outcome == ItemOutcome.RETURNED ? item.failureReason() : null;
    }

    private static boolean holds(AssignmentView assignment, UUID parcelId) {
        return assignment.items().stream().anyMatch(item -> item.parcelId().equals(parcelId));
    }

    private static Instant closedAt(AssignmentView assignment) {
        return assignment.completedAt() != null ? assignment.completedAt() : assignment.cancelledAt();
    }
}
