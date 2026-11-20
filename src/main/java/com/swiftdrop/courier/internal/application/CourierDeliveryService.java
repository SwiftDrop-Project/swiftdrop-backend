package com.swiftdrop.courier.internal.application;

import com.swiftdrop.courier.internal.application.CourierViews.CourierParcel;
import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.courier.internal.dto.StatusChangeRequest;
import com.swiftdrop.courier.internal.dto.UnableToCollectRequest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The courier's parcels: the ones in the dispatch tasks (assignments) a dispatcher gave them, read through
 * {@link AssignmentApi}, with each parcel's details and status from {@link DeliveryApi}. The dispatcher may group
 * several parcels in one task; the courier works each parcel on its own.
 *
 * <p>Ownership: a courier only sees a parcel that is (or was) in one of their own tasks; anything else is
 * {@code 404 NOT_FOUND}, so ids can't be probed.
 *
 * <p>Writes check, in order: the request (400), ownership (404), the task is open (409 ASSIGNMENT_CLOSED), the task
 * allows the step (409 TASK_STEP_NOT_ALLOWED), the parcel can make the move (409 INVALID_STATUS_TRANSITION). Then the
 * parcel status (delivery) and the task outcome (dispatch) are written in one transaction, so both or neither are
 * kept, and the task is completed once its last parcel is closed ({@link #completeIfFinished}).
 *
 * <p>{@code DeliveryApi} comes through an {@link ObjectProvider}, so the endpoints answer
 * {@code 503 SERVICE_UNAVAILABLE} when nothing implements it.
 */
@Service
public class CourierDeliveryService {

    /** The role name delivery records as the actor of a courier's change. */
    static final String ACTOR_ROLE = "COURIER";
    static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(CourierDeliveryService.class);

    /**
     * Open pickups first, then the other open steps (both in the order the dispatcher planned them), then the
     * finished ones (newest first).
     */
    private static final Comparator<CourierParcel> TODAY_ORDER = Comparator
            .comparingInt((CourierParcel parcel) -> group(parcel.task()))
            .thenComparing((a, b) -> a.task().closed()
                    ? compareNullsLast(b.task().updatedAt(), a.task().updatedAt())
                    : compareNullsLast(a.task().assignedAt(), b.task().assignedAt()))
            .thenComparingInt(parcel -> parcel.task().sequence());

    /** Completed parcels, newest completion first; ties in a stable order. */
    private static final Comparator<CourierTask> HISTORY_ORDER = Comparator
            .comparing(CourierTask::completedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(CourierTask::assignmentId)
            .thenComparingInt(CourierTask::sequence);

    private final ObjectProvider<DeliveryApi> deliveries;
    private final AssignmentApi assignments;
    private final Clock clock;

    @Autowired
    public CourierDeliveryService(ObjectProvider<DeliveryApi> deliveries, AssignmentApi assignments) {
        this(deliveries, assignments, Clock.system(CourierDay.ZONE));
    }

    CourierDeliveryService(ObjectProvider<DeliveryApi> deliveries, AssignmentApi assignments, Clock clock) {
        this.deliveries = deliveries;
        this.assignments = assignments;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenDeliveryIsMissing() {
        if (deliveries.getIfAvailable() == null) {
            log.warn("No DeliveryApi implementation: courier endpoints answer 503 SERVICE_UNAVAILABLE until delivery "
                    + "is implemented.");
        }
    }

    // ---- Reads ----

    /** The parcels in the courier's open tasks, plus the parcels they completed today (Sri Lankan time). */
    public CourierToday today(UUID courierId) {
        DeliveryApi delivery = delivery();
        Instant startOfToday = CourierDay.startOfToday(clock);
        Map<UUID, CourierTask> steps = new LinkedHashMap<>();
        for (AssignmentView task : assignments.findOpenForCourier(courierId, startOfToday)) {
            if (!courierId.equals(task.courierId()) || task.status() == AssignmentStatus.CANCELLED) {
                continue;
            }
            for (AssignmentItemView item : task.items()) {
                if (item.isClosed() && item.closedAt().isBefore(startOfToday)) {
                    continue;
                }
                // A parcel can be in two of the courier's tasks today (handed over at a branch, then given to them
                // again for the next leg): the open step wins.
                steps.merge(item.parcelId(), CourierTask.of(task, item), (kept, other) -> kept.closed() ? other : kept);
            }
        }
        Map<UUID, DeliverySnapshot> parcels = byId(delivery.findAllById(steps.keySet()));
        List<CourierParcel> list = steps.entrySet().stream()
                .filter(entry -> parcels.containsKey(entry.getKey()))
                .map(entry -> new CourierParcel(parcels.get(entry.getKey()), entry.getValue()))
                .sorted(TODAY_ORDER)
                .toList();
        return new CourierToday(CourierDay.today(clock), list);
    }

    /**
     * The parcels the courier completed (pickups and deliveries: handed over, not collected, delivered, returned, not
     * taken out), newest completion first. A parcel counts as soon as its own item is closed, even while the rest of
     * its task is still in progress. {@code trackingNumber} is optional; spaces and case are ignored, and an unknown
     * number gives an empty page.
     *
     * <p>Dispatch has no query for a courier's closed items across tasks, so this reads all the courier's tasks
     * ({@code findOpenForCourier} with the earliest possible close time: every open task and every closed one) and
     * pages the closed items here. With a tracking number it reads only that parcel's items.
     */
    public Page<CourierParcel> history(UUID courierId, int page, int size, String trackingNumber) {
        if (page < 0) {
            throw new FieldValidationException("page", "Use a page number of 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Use a page size from 1 to " + MAX_PAGE_SIZE + ".");
        }
        DeliveryApi delivery = delivery();
        PageRequest request = PageRequest.of(page, size);
        String number = normalizeTrackingNumber(trackingNumber);
        List<CourierTask> completed;
        if (number != null) {
            Optional<DeliverySnapshot> found = delivery.findByTrackingNumber(number);
            if (found.isEmpty()) {
                return Page.empty(request);
            }
            completed = completedSteps(courierId, found.get().id());
        } else {
            completed = completedSteps(courierId, assignments.findOpenForCourier(courierId, Instant.EPOCH));
        }

        int from = (int) Math.min(request.getOffset(), completed.size());
        int to = Math.min(from + size, completed.size());
        List<CourierTask> pageSteps = completed.subList(from, to);
        Map<UUID, DeliverySnapshot> parcels = byId(delivery.findAllById(
                pageSteps.stream().map(CourierTask::parcelId).collect(Collectors.toSet())));
        List<CourierParcel> list = pageSteps.stream()
                .filter(step -> parcels.containsKey(step.parcelId()))
                .map(step -> new CourierParcel(parcels.get(step.parcelId()), step))
                .toList();
        return new PageImpl<>(list, request, completed.size());
    }

    public ParcelDetail detail(UUID courierId, UUID parcelId) {
        DeliveryApi delivery = delivery();
        CourierTask task = step(courierId, parcelId);
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return new ParcelDetail(parcel, delivery.history(parcelId), task);
    }

    // ---- Writes ----

    /**
     * Moves the courier's parcel to the requested status: collected, out for delivery, delivered, failed, or handed
     * over / returned at the branch (AT_BRANCH, with who received it). Delivery applies it and dispatch records the
     * task outcome, in one transaction; the task completes when this was its last open parcel.
     */
    @Transactional
    public ParcelDetail changeStatus(UUID courierId, UUID parcelId, StatusChangeRequest request) {
        DeliveryApi delivery = delivery();
        ValidChange change = validate(request);
        CourierTask task = step(courierId, parcelId);
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        ParcelStatus target = request.status();
        requireOpen(task);
        if (!task.stepStatuses().contains(target)) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
        if (!parcel.status().canMoveTo(target) || !task.atTheRightBranch(target, parcel.currentBranch())) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        DeliverySnapshot updated;
        try {
            updated = delivery.changeStatus(new ChangeStatusCommand(parcelId, target, courierId, ACTOR_ROLE,
                    change.note(), change.failureReason(), change.recipientName(), null, task.branchFor(target)));
        } catch (ForbiddenException ex) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        AssignmentView after = assignments.recordOutcome(new RecordOutcomeCommand(task.assignmentId(), parcelId,
                courierId, task.outcomeFor(target), null,
                target == ParcelStatus.FAILED ? change.failureReason() : null, change.note(),
                target == ParcelStatus.AT_BRANCH ? change.receivedBy() : null,
                target == ParcelStatus.DELIVERED ? change.recipientName() : null));
        after = completeIfFinished(after, courierId);
        return new ParcelDetail(updated, delivery.history(parcelId), stepAfter(after, parcelId, task));
    }

    /**
     * "Unable to Collect": the courier couldn't take the parcel (sender away, parcel not ready, ...). Records
     * NOT_COLLECTED for a pickup or NOT_LOADED for a transfer or delivery, with the reason. The parcel status doesn't
     * change (the parcel never moved), the parcel is completed for the courier, and the task completes when this was
     * its last open parcel.
     */
    @Transactional
    public ParcelDetail unableToCollect(UUID courierId, UUID parcelId, UnableToCollectRequest request) {
        DeliveryApi delivery = delivery();
        TaskProblem problem = request.problem();
        String note = trimToNull(request.note());
        if (problem == TaskProblem.OTHER && (note == null || note.length() < 3)) {
            throw new FieldValidationException("note", "Describe what went wrong.");
        }
        CourierTask task = step(courierId, parcelId);
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        requireOpen(task);
        if (!task.canMarkUnableToCollect()) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
        AssignmentView after = assignments.recordOutcome(new RecordOutcomeCommand(task.assignmentId(), parcelId,
                courierId, task.unableToCollectOutcome(), problem, null, note, null, null));
        after = completeIfFinished(after, courierId);
        return new ParcelDetail(parcel, delivery.history(parcelId), stepAfter(after, parcelId, task));
    }

    /**
     * Completes the task once every parcel in it is closed (handed over, delivered, returned, or never taken). Whether
     * an item is closed, and whether a task may be completed, are dispatch's rules ({@code ItemOutcome.closes},
     * {@code AssignmentApi.complete}); this only asks dispatch to complete it at that moment, in the same
     * transaction as the courier's step.
     */
    private AssignmentView completeIfFinished(AssignmentView task, UUID courierId) {
        if (task == null || task.status().isClosed()) {
            return task;
        }
        if (task.items().stream().allMatch(AssignmentItemView::isClosed)) {
            return assignments.complete(task.id(), courierId);
        }
        return task;
    }

    // ---- Steps ----

    /**
     * The courier's step for this parcel: the open one if the parcel is in one of their open tasks, else the most
     * recent one. 404 when the parcel was never in their tasks.
     */
    private CourierTask step(UUID courierId, UUID parcelId) {
        CourierTask latest = null;
        for (AssignmentItemView item : assignments.findItemsForCourierAndParcel(courierId, parcelId)) {
            Optional<AssignmentView> task = own(courierId, item.assignmentId());
            if (task.isEmpty()) {
                continue;
            }
            CourierTask step = CourierTask.of(task.get(), item(task.get(), item));
            if (!step.closed()) {
                return step;
            }
            if (latest == null) {
                latest = step;
            }
        }
        if (latest == null) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        return latest;
    }

    /** The courier's completed steps for one parcel (any task, open or closed). */
    private List<CourierTask> completedSteps(UUID courierId, UUID parcelId) {
        return assignments.findItemsForCourierAndParcel(courierId, parcelId).stream()
                .flatMap(item -> own(courierId, item.assignmentId()).stream()
                        .map(task -> CourierTask.of(task, item(task, item))))
                .filter(CourierTask::completed)
                .sorted(HISTORY_ORDER)
                .toList();
    }

    /** The courier's completed steps in these tasks: every closed item, whether its task is open or closed. */
    private static List<CourierTask> completedSteps(UUID courierId, List<AssignmentView> tasks) {
        return tasks.stream()
                .filter(task -> courierId.equals(task.courierId()))
                .flatMap(task -> task.items().stream()
                        .filter(item -> item.outcome().closes(task.type()))
                        .map(item -> CourierTask.of(task, item)))
                .sorted(HISTORY_ORDER)
                .toList();
    }

    private Optional<AssignmentView> own(UUID courierId, UUID assignmentId) {
        return assignments.findByIdForCourier(assignmentId, courierId)
                .filter(task -> courierId.equals(task.courierId()));
    }

    /** The item as the task has it now (the task is read after the item list, so it is the newer of the two). */
    private static AssignmentItemView item(AssignmentView task, AssignmentItemView item) {
        return task.items().stream()
                .filter(candidate -> candidate.parcelId().equals(item.parcelId()))
                .findFirst()
                .orElse(item);
    }

    private static CourierTask stepAfter(AssignmentView task, UUID parcelId, CourierTask before) {
        if (task == null) {
            return before;
        }
        return task.items().stream()
                .filter(item -> item.parcelId().equals(parcelId))
                .findFirst()
                .map(item -> CourierTask.of(task, item))
                .orElse(before);
    }

    private static void requireOpen(CourierTask task) {
        if (task.taskClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
    }

    // ---- Helpers ----

    private static Map<UUID, DeliverySnapshot> byId(List<DeliverySnapshot> parcels) {
        return parcels.stream().collect(Collectors.toMap(DeliverySnapshot::id, Function.identity(), (a, b) -> a));
    }

    private DeliveryApi delivery() {
        DeliveryApi delivery = deliveries.getIfAvailable();
        if (delivery == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return delivery;
    }

    /** The rules that depend on the target status (API.md "Validation rules (courier)"). */
    private static ValidChange validate(StatusChangeRequest request) {
        ParcelStatus target = request.status();
        String note = trimToNull(request.note());
        String recipientName = trimToNull(request.recipientName());
        String receivedBy = trimToNull(request.receivedBy());
        FailureReason reason = request.failureReason();

        if (recipientName != null && target != ParcelStatus.DELIVERED) {
            throw new FieldValidationException("recipientName", "Only for delivered parcels.");
        }
        if (reason != null && target != ParcelStatus.FAILED) {
            throw new FieldValidationException("failureReason", "Only for failed deliveries.");
        }
        if (receivedBy != null && target != ParcelStatus.AT_BRANCH) {
            throw new FieldValidationException("receivedBy", "Only when you hand the parcel over at a branch.");
        }
        if (target == ParcelStatus.DELIVERED && (recipientName == null || recipientName.length() < 2)) {
            throw new FieldValidationException("recipientName", "Enter the name of the person who received the parcel.");
        }
        if (target == ParcelStatus.FAILED && reason == null) {
            throw new FieldValidationException("failureReason", "Select why the delivery failed.");
        }
        if (target == ParcelStatus.AT_BRANCH && (receivedBy == null || receivedBy.length() < 2)) {
            throw new FieldValidationException("receivedBy",
                    "Enter the name of the person at the branch who received the parcel.");
        }
        if (reason == FailureReason.OTHER && (note == null || note.length() < 3)) {
            throw new FieldValidationException("note", "Describe what went wrong.");
        }
        return new ValidChange(note, reason, recipientName, receivedBy);
    }

    private record ValidChange(String note, FailureReason failureReason, String recipientName, String receivedBy) {
    }

    static String normalizeTrackingNumber(String trackingNumber) {
        if (trackingNumber == null) {
            return null;
        }
        String normalized = trackingNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 0 = an open pickup, 1 = another open step, 2 = finished. */
    private static int group(CourierTask task) {
        if (task.closed()) {
            return 2;
        }
        return task.type() == AssignmentType.PICKUP ? 0 : 1;
    }

    private static int compareNullsLast(Instant a, Instant b) {
        return Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(a, b);
    }
}
