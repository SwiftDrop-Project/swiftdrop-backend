package com.swiftdrop.dispatch.internal.domain;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One parcel within a courier's assignment (dispatch.assignment_items) and what happened to it. Changed only through
 * {@link CourierAssignment}, so the assignment's rules (open, courier, version) always apply. parcelId is a delivery
 * parcel id (no foreign key).
 *
 * <p>The note, receivedBy and recipientName are personal data: no toString on purpose.
 */
@Entity
@Table(name = "assignment_items", schema = "dispatch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssignmentItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    @Getter(AccessLevel.NONE)
    private CourierAssignment assignment;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TaskProblem problem;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    @Column(length = 500)
    private String note;

    @Column(name = "received_by", length = 100)
    private String receivedBy;

    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private Long version;

    static AssignmentItem pending(UUID id, CourierAssignment assignment, UUID parcelId, int sequence, Instant now) {
        AssignmentItem item = new AssignmentItem();
        item.id = id;
        item.assignment = assignment;
        item.parcelId = parcelId;
        item.sequence = sequence;
        item.outcome = ItemOutcome.PENDING;
        item.updatedAt = now;
        return item;
    }

    /**
     * Moves the item to the command's outcome. The assignment has already checked that the step is allowed.
     * Each field is kept only for the outcomes it belongs to, so the table's CHECK constraints hold: the problem for
     * NOT_COLLECTED / NOT_LOADED, the failure reason for FAILED (and kept when the parcel is RETURNED), the
     * recipient for DELIVERED, who received it at the branch for HANDED_OVER / RETURNED. A note replaces the last
     * one; no note keeps it.
     *
     * @throws IllegalArgumentException when a field the outcome needs is missing (the caller checks the request, so
     *                                  this is a programming error)
     */
    void apply(RecordOutcomeCommand command, AssignmentType type, Instant now) {
        ItemOutcome target = command.outcome();
        boolean notTaken = target == ItemOutcome.NOT_COLLECTED || target == ItemOutcome.NOT_LOADED;
        if (notTaken && command.problem() == null) {
            throw new IllegalArgumentException(target + " needs a problem");
        }
        if (target == ItemOutcome.FAILED && command.failureReason() == null) {
            throw new IllegalArgumentException("FAILED needs a failure reason");
        }
        if (target == ItemOutcome.DELIVERED && command.recipientName() == null) {
            throw new IllegalArgumentException("DELIVERED needs the recipient's name");
        }
        this.outcome = target;
        this.problem = notTaken ? command.problem() : null;
        if (target == ItemOutcome.FAILED) {
            this.failureReason = command.failureReason();
        } else if (target != ItemOutcome.RETURNED) {
            this.failureReason = null;
        }
        this.recipientName = target == ItemOutcome.DELIVERED ? command.recipientName() : null;
        boolean atBranch = target == ItemOutcome.HANDED_OVER || target == ItemOutcome.RETURNED;
        this.receivedBy = atBranch ? command.receivedBy() : null;
        if (command.note() != null) {
            this.note = command.note();
        }
        this.updatedAt = now;
        this.closedAt = target.closes(type) ? now : null;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public UUID getAssignmentId() {
        return assignment.getId();
    }

    public AssignmentItemView toView() {
        return new AssignmentItemView(assignment.getId(), parcelId, sequence, outcome, problem, failureReason, note,
                receivedBy, recipientName, updatedAt, closedAt);
    }
}
