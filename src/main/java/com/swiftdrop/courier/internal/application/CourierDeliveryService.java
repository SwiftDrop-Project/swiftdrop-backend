package com.swiftdrop.courier.internal.application;

import com.swiftdrop.courier.internal.application.CourierViews.CourierParcel;
import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.courier.internal.dto.StatusChangeRequest;
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
 * {@link AssignmentApi}, with each parcel's details and status from {@link DeliveryApi}.
 *
 * <p>Ownership: a courier only sees a parcel that is (or was) in one of their own tasks; anything else is
 * {@code 404 NOT_FOUND}. A status change must be the next step of the task (pickup, transfer or delivery, see
 * {@link CourierTask}); it changes the parcel through delivery and records the task outcome in dispatch in one
 * transaction, and completes the task once every parcel in it is done.
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

    /** The parcels in the courier's open tasks, plus the steps they finished today (Sri Lankan time). */
    public CourierToday today(UUID courierId) {
        DeliveryApi delivery = delivery();
        Instant startOfToday = CourierDay.startOfToday(clock);
        Map<UUID, CourierTask> steps = new LinkedHashMap<>();
        for (AssignmentView task : assignments.findOpenForCourier(courierId, startOfToday)) {
            if (task.status() == AssignmentStatus.CANCELLED) {
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
     * Parcels the courier delivered or failed to deliver, most recent first. {@code trackingNumber} is optional;
     * spaces and case are ignored.
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
        UUID parcelId = null;
        String number = normalizeTrackingNumber(trackingNumber);
        if (number != null) {
            Optional<DeliverySnapshot> found = delivery.findByTrackingNumber(number);
            if (found.isEmpty()) {
                return Page.empty(request);
            }
            parcelId = found.get().id();
        }
        Page<AssignmentItemView> items = assignments.findFinishedDeliveriesForCourier(courierId, parcelId, request);
        Map<UUID, DeliverySnapshot> parcels = byId(delivery.findAllById(
                items.getContent().stream().map(AssignmentItemView::parcelId).collect(Collectors.toSet())));
        List<CourierParcel> list = items.getContent().stream()
                .filter(item -> parcels.containsKey(item.parcelId()))
                .map(item -> new CourierParcel(parcels.get(item.parcelId()), finishedStep(item)))
                .toList();
        return new PageImpl<>(list, request, items.getTotalElements());
    }

    public ParcelDetail detail(UUID courierId, UUID parcelId) {
        DeliveryApi delivery = delivery();
        CourierTask task = step(courierId, parcelId);
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return new ParcelDetail(parcel, delivery.history(parcelId), task);
    }

    /**
     * Moves the courier's parcel to the requested status. Order of checks: request rules (400), ownership (404), the
     * task's next step and the parcel's current status (409); then delivery applies it and dispatch records the
     * task outcome, in one transaction.
     */
    @Transactional
    public ParcelDetail changeStatus(UUID courierId, UUID parcelId, StatusChangeRequest request) {
        DeliveryApi delivery = delivery();
        ValidChange change = validate(request);
        CourierTask task = step(courierId, parcelId);
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        ParcelStatus target = request.status();
        if (!task.nextStatuses(parcel.status()).contains(target)) {
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
                target == ParcelStatus.FAILED ? change.failureReason() : null, change.note(), null,
                target == ParcelStatus.DELIVERED ? change.recipientName() : null));
        if (after != null && after.items().stream().allMatch(AssignmentItemView::isClosed)) {
            after = assignments.complete(after.id(), courierId);
        }
        return new ParcelDetail(updated, delivery.history(parcelId), stepAfter(after, parcelId, task));
    }

    /**
     * The courier's step for this parcel: the open one if the parcel is in one of their open tasks, else the most
     * recent one. 404 when the parcel was never in their tasks.
     */
    private CourierTask step(UUID courierId, UUID parcelId) {
        CourierTask latest = null;
        for (AssignmentItemView item : assignments.findItemsForCourierAndParcel(courierId, parcelId)) {
            Optional<AssignmentView> task = assignments.findByIdForCourier(item.assignmentId(), courierId);
            if (task.isEmpty()) {
                continue;
            }
            CourierTask step = CourierTask.of(task.get(), item);
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

    /** A history row is read-only, so its step is shown as closed. */
    private static CourierTask finishedStep(AssignmentItemView item) {
        return new CourierTask(item.assignmentId(), AssignmentType.DELIVERY, item.outcome(), true, null, null, null,
                item.sequence(), item.updatedAt());
    }

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
        FailureReason reason = request.failureReason();

        if (recipientName != null && target != ParcelStatus.DELIVERED) {
            throw new FieldValidationException("recipientName", "Only for delivered parcels.");
        }
        if (reason != null && target != ParcelStatus.FAILED) {
            throw new FieldValidationException("failureReason", "Only for failed deliveries.");
        }
        if (target == ParcelStatus.DELIVERED && (recipientName == null || recipientName.length() < 2)) {
            throw new FieldValidationException("recipientName", "Enter the name of the person who received the parcel.");
        }
        if (target == ParcelStatus.FAILED && reason == null) {
            throw new FieldValidationException("failureReason", "Select why the delivery failed.");
        }
        if (reason == FailureReason.OTHER && (note == null || note.length() < 3)) {
            throw new FieldValidationException("note", "Describe what went wrong.");
        }
        return new ValidChange(note, reason, recipientName);
    }

    private record ValidChange(String note, FailureReason failureReason, String recipientName) {
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
