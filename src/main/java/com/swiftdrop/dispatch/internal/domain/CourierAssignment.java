package com.swiftdrop.dispatch.internal.domain;

import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A courier's assignment (dispatch.courier_assignments) and its items, with the rules for creating it, recording what
 * happened to each parcel and completing it. courierId and createdBy are identity user ids, parcel ids are delivery
 * parcel ids, all plain UUIDs (no foreign keys).
 *
 * <p>Every change also sets {@code updatedAt}, so the assignment row is always written and its {@code version}
 * goes up even when only an item changed. Two changes to the same assignment at once therefore fail with an
 * optimistic-lock conflict instead of overwriting each other.
 *
 * <p>The note may hold personal data: no toString on purpose.
 */
@Entity
@Table(name = "courier_assignments", schema = "dispatch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourierAssignment {

    /** The most parcels one assignment may hold. */
    public static final int MAX_ITEMS = 50;

    @Id
    private UUID id;

    @Column(name = "courier_id", nullable = false)
    private UUID courierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_branch", length = 30)
    private District originBranch;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_branch", length = 30)
    private District destinationBranch;

    @Column(name = "scheduled_for", nullable = false)
    private LocalDate scheduledFor;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /** Loaded in batches when several assignments are read at once (courier lists, dispatcher pages). */
    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence")
    @BatchSize(size = 50)
    @Getter(AccessLevel.NONE)
    private List<AssignmentItem> items = new ArrayList<>();

    /**
     * A new assignment in ASSIGNED, with one PENDING item per parcel in the given order (sequence 1, 2, ...).
     * Checks the branch rule for the type. Checking that the courier is active and that each parcel suits the type
     * is the caller's job (it needs identity and delivery).
     *
     * @throws FieldValidationException originBranch / destinationBranch when the branches don't fit the type;
     *                                  parcelIds when the list is empty, too long or holds a parcel twice
     */
    public static CourierAssignment create(UUID id, UUID courierId, AssignmentType type, District originBranch,
                                           District destinationBranch, LocalDate scheduledFor, String note,
                                           UUID createdBy, List<UUID> parcelIds, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courierId, "courierId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(now, "now");
        BranchRules.check(type, originBranch, destinationBranch);
        checkParcels(parcelIds);

        CourierAssignment assignment = new CourierAssignment();
        assignment.id = id;
        assignment.courierId = courierId;
        assignment.type = type;
        assignment.status = AssignmentStatus.ASSIGNED;
        assignment.originBranch = originBranch;
        assignment.destinationBranch = destinationBranch;
        assignment.scheduledFor = scheduledFor;
        assignment.note = note;
        assignment.createdBy = createdBy;
        assignment.createdAt = now;
        assignment.updatedAt = now;
        int sequence = 1;
        for (UUID parcelId : parcelIds) {
            assignment.items.add(AssignmentItem.pending(UUID.randomUUID(), assignment, parcelId, sequence++, now));
        }
        return assignment;
    }

    /**
     * Records one outcome for one item (AssignmentApi.recordOutcome). The first outcome moves the assignment from
     * ASSIGNED to IN_PROGRESS; a closing outcome closes the item.
     *
     * @throws NotFoundException     NOT_FOUND when the command's courier is not this assignment's courier, or the
     *                               parcel is not in it
     * @throws BusinessRuleException ASSIGNMENT_CLOSED when the assignment is COMPLETED or CANCELLED;
     *                               TASK_STEP_NOT_ALLOWED when the item can't move to that outcome
     */
    public AssignmentItem recordOutcome(RecordOutcomeCommand command, Instant now) {
        if (!courierId.equals(command.courierId()) || !id.equals(command.assignmentId())) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        AssignmentItem item = item(command.parcelId());
        requireOpen();
        if (!item.getOutcome().canMoveTo(type, command.outcome())) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
        item.apply(command, type, now);
        if (status == AssignmentStatus.ASSIGNED) {
            status = AssignmentStatus.IN_PROGRESS;
            startedAt = now;
        }
        updatedAt = now;
        return item;
    }

    /**
     * Completes the assignment (AssignmentApi.complete). Allowed only when every item is closed, even when some
     * parcels were never taken. No parcel status changes.
     *
     * @throws BusinessRuleException ASSIGNMENT_CLOSED when it is already COMPLETED or CANCELLED;
     *                               ASSIGNMENT_NOT_FINISHED when an item is still open
     */
    public void complete(Instant now) {
        requireOpen();
        if (items.stream().anyMatch(item -> !item.isClosed())) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_NOT_FINISHED);
        }
        status = AssignmentStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    /**
     * Adds parcels at the end of an open assignment (dispatcher). Checking that each parcel suits the type and is
     * in no other open assignment is the caller's job.
     *
     * @throws BusinessRuleException    ASSIGNMENT_CLOSED when the assignment is COMPLETED or CANCELLED
     * @throws FieldValidationException parcelIds when the list is empty or holds a parcel twice, when a parcel is
     *                                  already in this assignment, or when the assignment would go over
     *                                  {@link #MAX_ITEMS}
     */
    public void addParcels(List<UUID> parcelIds, Instant now) {
        requireOpen();
        checkParcels(parcelIds);
        if (parcelIds.stream().anyMatch(this::holds)) {
            throw new FieldValidationException("parcelIds", "A parcel you selected is already in this task.");
        }
        if (items.size() + parcelIds.size() > MAX_ITEMS) {
            throw new FieldValidationException("parcelIds",
                    "A task can hold up to " + MAX_ITEMS + " parcels.");
        }
        int sequence = items.stream().mapToInt(AssignmentItem::getSequence).max().orElse(0) + 1;
        for (UUID parcelId : parcelIds) {
            items.add(AssignmentItem.pending(UUID.randomUUID(), this, parcelId, sequence++, now));
        }
        updatedAt = now;
    }

    /**
     * Takes a parcel the courier hasn't touched yet out of an open assignment (dispatcher). The parcel goes back to
     * the dispatcher's queue.
     *
     * @throws NotFoundException     NOT_FOUND when the parcel is not in the assignment
     * @throws BusinessRuleException ASSIGNMENT_CLOSED when the assignment is COMPLETED or CANCELLED;
     *                               TASK_STEP_NOT_ALLOWED when the courier already recorded something for it;
     *                               ASSIGNMENT_NEEDS_PARCEL when it is the last parcel (cancel the task instead)
     */
    public void removeParcel(UUID parcelId, Instant now) {
        AssignmentItem item = item(parcelId);
        requireOpen();
        if (item.getOutcome() != ItemOutcome.PENDING) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED,
                    "The courier has already updated this parcel, so it can't be taken out of the task.");
        }
        if (items.size() == 1) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_NEEDS_PARCEL);
        }
        items.remove(item);
        updatedAt = now;
    }

    /**
     * Gives an assignment the courier hasn't started to another courier (dispatcher). Checking that the new courier
     * is active is the caller's job. The same courier again changes nothing.
     *
     * @throws BusinessRuleException ASSIGNMENT_CLOSED when it is COMPLETED or CANCELLED; ASSIGNMENT_STARTED when
     *                               the courier already recorded an outcome
     */
    public void reassign(UUID newCourierId, Instant now) {
        Objects.requireNonNull(newCourierId, "newCourierId");
        requireNotStarted();
        if (!courierId.equals(newCourierId)) {
            courierId = newCourierId;
            updatedAt = now;
        }
    }

    /**
     * Cancels an assignment the courier hasn't started (dispatcher). Every item closes, so its parcels go back to
     * the dispatcher's queue.
     *
     * @throws BusinessRuleException ASSIGNMENT_CLOSED when it is COMPLETED or CANCELLED; ASSIGNMENT_STARTED when
     *                               the courier already recorded an outcome
     */
    public void cancel(Instant now) {
        requireNotStarted();
        items.forEach(item -> item.closeForCancel(now));
        status = AssignmentStatus.CANCELLED;
        cancelledAt = now;
        updatedAt = now;
    }

    /** Whether the parcel is in this assignment (open or closed). */
    public boolean holds(UUID parcelId) {
        return items.stream().anyMatch(item -> item.getParcelId().equals(parcelId));
    }

    /** How many items are still open. */
    public long openItemCount() {
        return items.stream().filter(item -> !item.isClosed()).count();
    }

    /** The item for this parcel. @throws NotFoundException NOT_FOUND when the parcel is not in the assignment */
    public AssignmentItem item(UUID parcelId) {
        return items.stream()
                .filter(item -> item.getParcelId().equals(parcelId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    /** The items by sequence, read-only. */
    public List<AssignmentItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** When the assignment was closed (completed or cancelled), or null while it is open. */
    public Instant closedAt() {
        return completedAt != null ? completedAt : cancelledAt;
    }

    /** The read-only copy that other modules get. An assignment that was never saved has version 0. */
    public AssignmentView toView() {
        return new AssignmentView(id, courierId, type, status, originBranch, destinationBranch, scheduledFor, note,
                createdAt, startedAt, completedAt, cancelledAt,
                items.stream().map(AssignmentItem::toView).toList(),
                version == null ? 0 : version);
    }

    private void requireOpen() {
        if (status.isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
    }

    private void requireNotStarted() {
        requireOpen();
        if (status != AssignmentStatus.ASSIGNED) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_STARTED);
        }
    }

    private static void checkParcels(List<UUID> parcelIds) {
        if (parcelIds == null || parcelIds.isEmpty()) {
            throw new FieldValidationException("parcelIds", "Select at least one parcel.");
        }
        if (parcelIds.size() > MAX_ITEMS) {
            throw new FieldValidationException("parcelIds",
                    "A task can hold up to " + MAX_ITEMS + " parcels.");
        }
        // stream, not contains(null): List.of(...).contains(null) throws instead of answering false
        if (parcelIds.stream().anyMatch(Objects::isNull) || new HashSet<>(parcelIds).size() != parcelIds.size()) {
            throw new FieldValidationException("parcelIds", "Select each parcel only once.");
        }
    }
}
