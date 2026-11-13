package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.internal.domain.CourierAssignment;
import com.swiftdrop.dispatch.internal.dto.AddParcelsRequest;
import com.swiftdrop.dispatch.internal.dto.AssignmentDetailResponse;
import com.swiftdrop.dispatch.internal.dto.CreateAssignmentRequest;
import com.swiftdrop.dispatch.internal.dto.ParcelAssignmentResponse;
import com.swiftdrop.dispatch.internal.persistence.AssignmentItemRepository;
import com.swiftdrop.dispatch.internal.persistence.CourierAssignmentRepository;
import com.swiftdrop.identity.CourierSummary;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The dispatcher's writes: create courier tasks, add or remove parcels, give a task to another courier, cancel it,
 * and the one-step "assign this parcel to a courier".
 *
 * <p>Couriers are checked through {@link IdentityApi#requireAssignableCourier} (blocked and removed couriers are
 * rejected even when their id is sent directly). Parcels are checked through {@link DeliveryApi}: each must suit
 * the task ({@link ParcelRules}) and be in no other open task. The partial unique index
 * {@code uk_assignment_items_open_parcel} is the final guard when two dispatchers assign the same parcel at once.
 */
@Service
public class DispatchService {

    private final IdentityApi identity;
    private final DeliveryApi deliveries;
    private final CourierAssignmentRepository assignments;
    private final AssignmentItemRepository items;
    private final DispatchViews views;
    private final Clock clock;

    @Autowired
    public DispatchService(IdentityApi identity, DeliveryApi deliveries, CourierAssignmentRepository assignments,
                           AssignmentItemRepository items, DispatchViews views) {
        this(identity, deliveries, assignments, items, views, Clock.systemUTC());
    }

    DispatchService(IdentityApi identity, DeliveryApi deliveries, CourierAssignmentRepository assignments,
                    AssignmentItemRepository items, DispatchViews views, Clock clock) {
        this.identity = identity;
        this.deliveries = deliveries;
        this.assignments = assignments;
        this.items = items;
        this.views = views;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CourierSummary> listActiveCouriers(District branch) {
        return branch == null ? identity.listActiveCouriers() : identity.listActiveCouriersByBranch(branch);
    }

    /**
     * Gives one parcel to a courier for its next step: a one-parcel task of the type the parcel needs
     * ({@link ParcelRules#nextLeg}), scheduled for today.
     *
     * @throws NotFoundException     NOT_FOUND for an unknown courier or parcel
     * @throws BusinessRuleException COURIER_NOT_ASSIGNABLE for a blocked or removed courier; PARCEL_NOT_ASSIGNABLE
     *                               for a delivered parcel; PARCEL_NOT_READY_FOR_TASK when the parcel is on its way
     * @throws ConflictException     PARCEL_ALREADY_ASSIGNED when the parcel is in another open task
     */
    @Transactional
    public ParcelAssignmentResponse assignCourier(UUID parcelId, UUID courierId, UUID actorId) {
        identity.requireAssignableCourier(courierId);
        DeliverySnapshot parcel = deliveries.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (parcel.status().isFinished()) {
            throw new BusinessRuleException(ErrorCode.PARCEL_NOT_ASSIGNABLE,
                    "This parcel has been delivered, so it can't be assigned again.");
        }
        ParcelRules.Leg leg = ParcelRules.nextLeg(parcel);
        requireNotInOpenTask(List.of(parcel));
        Instant now = clock.instant();
        CourierAssignment assignment = CourierAssignment.create(UUID.randomUUID(), courierId, leg.type(),
                leg.origin(), leg.destination(), DispatchDay.today(clock), null, actorId, List.of(parcelId), now);
        insert(assignment);
        return new ParcelAssignmentResponse(parcel.id(), parcel.trackingNumber(), parcel.status(), courierId, now,
                assignment.getId(), assignment.getType());
    }

    /**
     * Creates a courier task. Checked in this order: the courier, the date, the branches for the type, then each
     * parcel (exists, suits the type, in no other open task).
     */
    @Transactional
    public AssignmentDetailResponse create(CreateAssignmentRequest request, UUID actorId) {
        identity.requireAssignableCourier(request.courierId());
        LocalDate today = DispatchDay.today(clock);
        LocalDate scheduledFor = request.scheduledFor() == null ? today : request.scheduledFor();
        if (scheduledFor.isBefore(today)) {
            throw new FieldValidationException("scheduledFor", "Choose today or a later date.");
        }
        CourierAssignment assignment = CourierAssignment.create(UUID.randomUUID(), request.courierId(),
                request.type(), request.originBranch(), request.destinationBranch(), scheduledFor,
                blankToNull(request.note()), actorId, request.parcelIds(), clock.instant());
        requireParcelsSuit(request.parcelIds(), assignment.getType(), assignment.getOriginBranch(),
                assignment.getDestinationBranch());
        insert(assignment);
        return views.detail(assignment);
    }

    /** Adds parcels to an open task (ASSIGNED or IN_PROGRESS). */
    @Transactional
    public AssignmentDetailResponse addParcels(UUID assignmentId, AddParcelsRequest request) {
        CourierAssignment assignment = find(assignmentId);
        if (assignment.getStatus().isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
        if (request.parcelIds().stream().anyMatch(assignment::holds)) {
            throw new FieldValidationException("parcelIds", "A parcel you selected is already in this task.");
        }
        // Check before changing the task: the "open parcel" query would otherwise flush the new items first.
        requireParcelsSuit(request.parcelIds(), assignment.getType(), assignment.getOriginBranch(),
                assignment.getDestinationBranch());
        assignment.addParcels(request.parcelIds(), clock.instant());
        return views.detail(update(assignment));
    }

    /** Takes a parcel the courier hasn't touched out of an open task. */
    @Transactional
    public AssignmentDetailResponse removeParcel(UUID assignmentId, UUID parcelId) {
        CourierAssignment assignment = find(assignmentId);
        assignment.removeParcel(parcelId, clock.instant());
        return views.detail(update(assignment));
    }

    /** Gives a task the courier hasn't started to another active courier. */
    @Transactional
    public AssignmentDetailResponse reassign(UUID assignmentId, UUID courierId) {
        CourierAssignment assignment = find(assignmentId);
        identity.requireAssignableCourier(courierId);
        assignment.reassign(courierId, clock.instant());
        return views.detail(update(assignment));
    }

    /** Cancels a task the courier hasn't started; its parcels go back to the queue. */
    @Transactional
    public AssignmentDetailResponse cancel(UUID assignmentId) {
        CourierAssignment assignment = find(assignmentId);
        assignment.cancel(clock.instant());
        return views.detail(update(assignment));
    }

    private CourierAssignment find(UUID assignmentId) {
        return assignments.findById(assignmentId).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    /**
     * Every parcel exists, suits the task and is in no other open task.
     *
     * @throws NotFoundException     NOT_FOUND when a parcel is unknown
     * @throws BusinessRuleException PARCEL_NOT_READY_FOR_TASK when a parcel doesn't suit the task
     * @throws ConflictException     PARCEL_ALREADY_ASSIGNED when a parcel is in another open task
     */
    private void requireParcelsSuit(List<UUID> parcelIds, AssignmentType type, District origin,
                                    District destination) {
        Map<UUID, DeliverySnapshot> parcels = views.parcels(parcelIds);
        if (parcels.size() != Set.copyOf(parcelIds).size()) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        List<DeliverySnapshot> ordered = parcelIds.stream().map(parcels::get).toList();
        ordered.forEach(parcel -> ParcelRules.requireSuits(parcel, type, origin, destination));
        requireNotInOpenTask(ordered);
    }

    private void requireNotInOpenTask(Collection<DeliverySnapshot> parcels) {
        Set<UUID> open = items.findOpenParcelIds(parcels.stream().map(DeliverySnapshot::id).toList());
        parcels.stream().filter(parcel -> open.contains(parcel.id())).findFirst().ifPresent(parcel -> {
            throw alreadyAssigned(parcel.trackingNumber());
        });
    }

    /** Saves a new task. Two dispatchers racing for one parcel: the loser gets PARCEL_ALREADY_ASSIGNED. */
    private void insert(CourierAssignment assignment) {
        try {
            assignments.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException e) {
            throw alreadyAssigned(null);
        }
    }

    /** Saves a changed task now, so a clash with another change gives a clear 409. */
    private CourierAssignment update(CourierAssignment assignment) {
        try {
            return assignments.saveAndFlush(assignment);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConflictException(ErrorCode.ASSIGNMENT_CHANGED);
        } catch (DataIntegrityViolationException e) {
            throw alreadyAssigned(null);
        }
    }

    private static ConflictException alreadyAssigned(String trackingNumber) {
        return trackingNumber == null
                ? new ConflictException(ErrorCode.PARCEL_ALREADY_ASSIGNED)
                : new ConflictException(ErrorCode.PARCEL_ALREADY_ASSIGNED, "Parcel " + trackingNumber
                + " is already in another courier's task. Refresh to see the latest queue.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
