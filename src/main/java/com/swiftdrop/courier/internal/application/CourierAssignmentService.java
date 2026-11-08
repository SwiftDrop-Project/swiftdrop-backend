package com.swiftdrop.courier.internal.application;

import com.swiftdrop.courier.internal.application.CourierViews.AssignmentDetail;
import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.application.ParcelMoves.ParcelMove;
import com.swiftdrop.courier.internal.dto.HandoverRequest;
import com.swiftdrop.courier.internal.dto.ItemOutcomeRequest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.swiftdrop.courier.internal.application.CourierSupport.ACTOR_ROLE;
import static com.swiftdrop.courier.internal.application.CourierSupport.required;
import static com.swiftdrop.courier.internal.application.CourierSupport.trimToNull;

/**
 * The courier's assignments (tasks): read them, record what happened to each parcel, hand parcels over at a branch
 * and complete the task. Assignments are read and changed only through dispatch's {@link AssignmentApi}; parcels only
 * through {@link DeliveryApi}.
 *
 * <p>Ownership: a courier sees only their own assignments, and only the parcels in them; anything else is
 * {@code 404 NOT_FOUND}, so ids can't be probed.
 *
 * <p>Writes: check order request (400) → mine (404) → assignment open (409 ASSIGNMENT_CLOSED) → step allowed
 * (409 TASK_STEP_NOT_ALLOWED) → parcel transition (409 INVALID_STATUS_TRANSITION); then the parcel status
 * ({@link ParcelMoves}) and the item outcome are written in one transaction, so both or neither are kept.
 *
 * <p>Both APIs come through an {@link ObjectProvider} because nothing implements them until WP3 and WP8 are merged
 * (or the temporary fakes are on). The application still starts; the endpoints answer
 * {@code 503 SERVICE_UNAVAILABLE}. Replace them with constructor injection once both are implemented.
 */
@Service
public class CourierAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(CourierAssignmentService.class);

    /** In progress first, then assigned (oldest first), then closed today (newest first). */
    private static final Comparator<AssignmentView> TODAY_ORDER = Comparator
            .comparingInt(CourierAssignmentService::group)
            .thenComparing((a, b) -> group(a) == 2
                    ? compareNullsLast(closedAt(b), closedAt(a))
                    : compareNullsLast(a.assignedAt(), b.assignedAt()));

    private final ObjectProvider<AssignmentApi> assignments;
    private final ObjectProvider<DeliveryApi> deliveries;
    private final Clock clock;

    @Autowired
    public CourierAssignmentService(ObjectProvider<AssignmentApi> assignments,
                                    ObjectProvider<DeliveryApi> deliveries) {
        this(assignments, deliveries, Clock.system(CourierDay.ZONE));
    }

    CourierAssignmentService(ObjectProvider<AssignmentApi> assignments, ObjectProvider<DeliveryApi> deliveries,
                             Clock clock) {
        this.assignments = assignments;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenAnApiIsMissing() {
        if (assignments.getIfAvailable() == null) {
            log.warn("No AssignmentApi implementation: courier endpoints answer 503 SERVICE_UNAVAILABLE until "
                    + "dispatch is implemented (or SWIFTDROP_FAKE_DISPATCH=true for demo data).");
        }
        if (deliveries.getIfAvailable() == null) {
            log.warn("No DeliveryApi implementation: courier endpoints answer 503 SERVICE_UNAVAILABLE until delivery "
                    + "is implemented (or SWIFTDROP_FAKE_DELIVERY=true for demo data).");
        }
    }

    // ---- Reads ----

    /** Open assignments, plus the ones completed or cancelled today (Sri Lankan time). */
    public CourierToday today(UUID courierId) {
        List<AssignmentView> list = required(assignments)
                .findOpenForCourier(courierId, CourierDay.startOfToday(clock))
                .stream()
                .filter(assignment -> courierId.equals(assignment.courierId()))
                .sorted(TODAY_ORDER)
                .toList();
        return new CourierToday(CourierDay.today(clock), list);
    }

    /**
     * Completed and cancelled assignments, newest first. {@code trackingNumber} is optional (spaces and case are
     * ignored): only assignments that held that parcel; an unknown number gives an empty page.
     */
    public Page<AssignmentView> history(UUID courierId, int page, int size, String trackingNumber) {
        PageRequest pageable = CourierSupport.pageRequest(page, size);
        AssignmentApi assignmentApi = required(assignments);
        String normalized = CourierSupport.normalizeTrackingNumber(trackingNumber);
        UUID parcelId = null;
        if (normalized != null) {
            Optional<DeliverySnapshot> parcel = required(deliveries).findByTrackingNumber(normalized);
            if (parcel.isEmpty()) {
                return Page.empty(pageable);
            }
            parcelId = parcel.get().id();
        }
        return assignmentApi.findClosedForCourier(courierId, parcelId, pageable);
    }

    public AssignmentDetail detail(UUID courierId, UUID assignmentId) {
        AssignmentApi assignmentApi = required(assignments);
        DeliveryApi delivery = required(deliveries);
        return withParcels(delivery, own(assignmentApi, assignmentId, courierId));
    }

    // ---- Writes ----

    /** Records what happened to one parcel (not a handover: that is {@link #handover}). */
    @Transactional
    public AssignmentDetail recordOutcome(UUID courierId, UUID assignmentId, UUID parcelId,
                                          ItemOutcomeRequest request) {
        AssignmentApi assignmentApi = required(assignments);
        DeliveryApi delivery = required(deliveries);
        ValidOutcome valid = validate(request);

        AssignmentView assignment = own(assignmentApi, assignmentId, courierId);
        AssignmentItemView item = item(assignment, parcelId);
        requireOpen(assignment);
        if (!item.outcome().canMoveTo(assignment.type(), valid.outcome())) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        ParcelMove move = ParcelMoves.forOutcome(assignment, valid.outcome());
        checkParcelCanMove(assignment, valid.outcome(), parcel, move);

        if (move != null) {
            delivery.changeStatus(new ChangeStatusCommand(parcelId, move.status(), courierId, ACTOR_ROLE,
                    valid.note(), valid.failureReason(), valid.recipientName(), null, move.branch()));
        }
        AssignmentView updated = assignmentApi.recordOutcome(new RecordOutcomeCommand(assignmentId, parcelId,
                courierId, valid.outcome(), valid.problem(), valid.failureReason(), valid.note(), null,
                valid.recipientName()));
        return withParcels(delivery, updated);
    }

    /**
     * Hands the selected parcels over at the branch: HANDED_OVER at the destination (PICKUP, TRANSFER) or RETURNED to
     * the origin (failed DELIVERY parcels). All or nothing: every parcel must be ready, or nothing changes.
     */
    @Transactional
    public AssignmentDetail handover(UUID courierId, UUID assignmentId, HandoverRequest request) {
        AssignmentApi assignmentApi = required(assignments);
        DeliveryApi delivery = required(deliveries);
        List<UUID> parcelIds = request.parcelIds();
        if (new LinkedHashSet<>(parcelIds).size() != parcelIds.size()) {
            throw new FieldValidationException("parcelIds", "Select each parcel only once.");
        }
        String receivedBy = trimToNull(request.receivedBy());
        if (receivedBy == null || receivedBy.length() < 2) {
            throw new FieldValidationException("receivedBy",
                    "Enter the name of the person at the branch who received the parcels.");
        }
        String note = trimToNull(request.note());

        AssignmentView assignment = own(assignmentApi, assignmentId, courierId);
        List<AssignmentItemView> items = parcelIds.stream().map(id -> item(assignment, id)).toList();
        requireOpen(assignment);
        ItemOutcome outcome = ParcelMoves.handoverOutcome(assignment.type());
        for (AssignmentItemView item : items) {
            if (!ParcelMoves.readyForHandover(assignment, item)) {
                throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
            }
        }
        Map<UUID, DeliverySnapshot> parcels = byId(delivery.findAllById(parcelIds));
        ParcelMove move = ParcelMoves.forOutcome(assignment, outcome);
        for (UUID parcelId : parcelIds) {
            DeliverySnapshot parcel = parcels.get(parcelId);
            if (parcel == null) {
                throw new NotFoundException(ErrorCode.NOT_FOUND);
            }
            checkParcelCanMove(assignment, outcome, parcel, move);
        }

        AssignmentView updated = assignment;
        for (UUID parcelId : parcelIds) {
            delivery.changeStatus(new ChangeStatusCommand(parcelId, move.status(), courierId, ACTOR_ROLE, note,
                    null, null, null, move.branch()));
            updated = assignmentApi.recordOutcome(new RecordOutcomeCommand(assignmentId, parcelId, courierId,
                    outcome, null, null, note, receivedBy, null));
        }
        return withParcels(delivery, updated);
    }

    /**
     * Completes the assignment once every item is closed (handed over, delivered, returned, or never taken).
     * Completing it changes no parcel status.
     */
    @Transactional
    public AssignmentView complete(UUID courierId, UUID assignmentId) {
        AssignmentApi assignmentApi = required(assignments);
        AssignmentView assignment = own(assignmentApi, assignmentId, courierId);
        requireOpen(assignment);
        if (assignment.items().stream().anyMatch(item -> !item.isClosed())) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_NOT_FINISHED);
        }
        return assignmentApi.complete(assignmentId, courierId);
    }

    // ---- Checks ----

    private static AssignmentView own(AssignmentApi assignmentApi, UUID assignmentId, UUID courierId) {
        return assignmentApi.findByIdForCourier(assignmentId, courierId)
                .filter(assignment -> courierId.equals(assignment.courierId()))
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private static AssignmentItemView item(AssignmentView assignment, UUID parcelId) {
        return assignment.items().stream()
                .filter(item -> item.parcelId().equals(parcelId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private static void requireOpen(AssignmentView assignment) {
        if (assignment.status().isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
    }

    /** The parcel must be able to take the move; loading also needs it at the assignment's origin branch. */
    private static void checkParcelCanMove(AssignmentView assignment, ItemOutcome outcome, DeliverySnapshot parcel,
                                           ParcelMove move) {
        if (move == null) {
            return;
        }
        boolean allowed = parcel.status().canMoveTo(move.status());
        if (allowed && ParcelMoves.needsOriginBranch(outcome)) {
            allowed = assignment.originBranch() != null && assignment.originBranch() == parcel.currentBranch();
        }
        if (!allowed) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    /** The rules that depend on the outcome (API.md "Courier", validation table). */
    private static ValidOutcome validate(ItemOutcomeRequest request) {
        ItemOutcome outcome = request.outcome();
        TaskProblem problem = request.problem();
        FailureReason reason = request.failureReason();
        String note = trimToNull(request.note());
        String recipientName = trimToNull(request.recipientName());

        if (outcome == ItemOutcome.PENDING) {
            throw new FieldValidationException("outcome", "Select what happened.");
        }
        if (ParcelMoves.isHandover(outcome)) {
            throw new FieldValidationException("outcome", "Use Hand over for this step.");
        }
        boolean notTaken = outcome == ItemOutcome.NOT_COLLECTED || outcome == ItemOutcome.NOT_LOADED;
        if (problem != null && !notTaken) {
            throw new FieldValidationException("problem", "Only when a parcel couldn't be collected or loaded.");
        }
        if (recipientName != null && outcome != ItemOutcome.DELIVERED) {
            throw new FieldValidationException("recipientName", "Only for delivered parcels.");
        }
        if (reason != null && outcome != ItemOutcome.FAILED) {
            throw new FieldValidationException("failureReason", "Only for failed deliveries.");
        }
        if (notTaken && problem == null) {
            throw new FieldValidationException("problem", "Select why you couldn't take the parcel.");
        }
        if (outcome == ItemOutcome.DELIVERED && (recipientName == null || recipientName.length() < 2)) {
            throw new FieldValidationException("recipientName", "Enter the name of the person who received the parcel.");
        }
        if (outcome == ItemOutcome.FAILED && reason == null) {
            throw new FieldValidationException("failureReason", "Select why the delivery failed.");
        }
        if ((problem == TaskProblem.OTHER || reason == FailureReason.OTHER) && (note == null || note.length() < 3)) {
            throw new FieldValidationException("note", "Describe what went wrong.");
        }
        return new ValidOutcome(outcome, problem, reason, note, recipientName);
    }

    private record ValidOutcome(ItemOutcome outcome, TaskProblem problem, FailureReason failureReason, String note,
                                String recipientName) {
    }

    // ---- Helpers ----

    private static AssignmentDetail withParcels(DeliveryApi delivery, AssignmentView assignment) {
        List<UUID> parcelIds = assignment.items().stream().map(AssignmentItemView::parcelId).toList();
        Map<UUID, DeliverySnapshot> parcels = byId(delivery.findAllById(parcelIds));
        if (parcels.size() < parcelIds.size()) {
            log.warn("Assignment {} lists {} parcel(s) that delivery doesn't know; they are left out",
                    assignment.id(), parcelIds.size() - parcels.size());
        }
        return new AssignmentDetail(assignment, parcels);
    }

    private static Map<UUID, DeliverySnapshot> byId(List<DeliverySnapshot> parcels) {
        Map<UUID, DeliverySnapshot> result = new HashMap<>();
        for (DeliverySnapshot parcel : parcels) {
            result.put(parcel.id(), parcel);
        }
        return result;
    }

    /** 0 = in progress, 1 = assigned, 2 = completed or cancelled. */
    private static int group(AssignmentView assignment) {
        if (assignment.status().isClosed()) {
            return 2;
        }
        return assignment.status() == AssignmentStatus.IN_PROGRESS ? 0 : 1;
    }

    private static Instant closedAt(AssignmentView assignment) {
        return assignment.completedAt() != null ? assignment.completedAt() : assignment.cancelledAt();
    }

    private static int compareNullsLast(Instant a, Instant b) {
        return Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(a, b);
    }
}
