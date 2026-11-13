package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.internal.domain.AssignmentItem;
import com.swiftdrop.dispatch.internal.domain.CourierAssignment;
import com.swiftdrop.dispatch.internal.domain.PickupQueueEntry;
import com.swiftdrop.dispatch.internal.dto.AssignmentDetailResponse;
import com.swiftdrop.dispatch.internal.dto.AssignmentSummaryResponse;
import com.swiftdrop.dispatch.internal.dto.CourierRef;
import com.swiftdrop.dispatch.internal.dto.CourierWorkloadResponse;
import com.swiftdrop.dispatch.internal.dto.DeliveryIssueResponse;
import com.swiftdrop.dispatch.internal.dto.DispatchOverviewResponse;
import com.swiftdrop.dispatch.internal.dto.QueueKind;
import com.swiftdrop.dispatch.internal.dto.QueueParcelResponse;
import com.swiftdrop.dispatch.internal.persistence.AssignmentItemRepository;
import com.swiftdrop.dispatch.internal.persistence.CourierAssignmentRepository;
import com.swiftdrop.dispatch.internal.persistence.CourierAssignmentRepository.CourierCount;
import com.swiftdrop.dispatch.internal.persistence.PickupQueueRepository;
import com.swiftdrop.identity.CourierSummary;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The dispatcher's reads: the parcel queues, the task list and detail, courier workloads, overview and issues. */
@Service
@Transactional(readOnly = true)
public class DispatchQueryService {

    /** The largest page of tasks or issues returned at once. */
    static final int MAX_PAGE_SIZE = 100;

    /** Outcomes the dispatcher has to look at: the parcel wasn't taken, or the delivery attempt failed. */
    static final Set<ItemOutcome> ISSUE_OUTCOMES =
            EnumSet.of(ItemOutcome.NOT_COLLECTED, ItemOutcome.NOT_LOADED, ItemOutcome.FAILED, ItemOutcome.RETURNED);

    private final DeliveryApi deliveries;
    private final IdentityApi identity;
    private final CourierAssignmentRepository assignments;
    private final AssignmentItemRepository items;
    private final PickupQueueRepository pickupQueue;
    private final DispatchViews views;
    private final Clock clock;

    @Autowired
    public DispatchQueryService(DeliveryApi deliveries, IdentityApi identity, CourierAssignmentRepository assignments,
                                AssignmentItemRepository items, PickupQueueRepository pickupQueue,
                                DispatchViews views) {
        this(deliveries, identity, assignments, items, pickupQueue, views, Clock.systemUTC());
    }

    DispatchQueryService(DeliveryApi deliveries, IdentityApi identity, CourierAssignmentRepository assignments,
                         AssignmentItemRepository items, PickupQueueRepository pickupQueue, DispatchViews views,
                         Clock clock) {
        this.deliveries = deliveries;
        this.identity = identity;
        this.assignments = assignments;
        this.items = items;
        this.pickupQueue = pickupQueue;
        this.views = views;
        this.clock = clock;
    }

    /**
     * Parcels at or around the branch waiting for a task, oldest first, leaving out those already in an open task.
     * PICKUP: new parcels whose sender is in the branch's district. AT_BRANCH: parcels at the branch.
     */
    public List<QueueParcelResponse> queue(District branch, QueueKind kind) {
        List<DeliverySnapshot> waiting = kind == QueueKind.PICKUP ? waitingForPickup(branch) : waitingAt(branch);
        Set<UUID> open = waiting.isEmpty() ? Set.of()
                : items.findOpenParcelIds(waiting.stream().map(DeliverySnapshot::id).toList());
        return waiting.stream()
                .filter(parcel -> !open.contains(parcel.id()))
                .map(parcel -> DispatchViews.queueRow(parcel,
                        kind == QueueKind.PICKUP ? parcel.createdAt() : parcel.updatedAt()))
                .toList();
    }

    /** Courier tasks, newest first, filtered by any of the given values (null means any). */
    public PageResponse<AssignmentSummaryResponse> list(AssignmentStatus status, AssignmentType type,
                                                        UUID courierId, District branch, LocalDate scheduledFor,
                                                        int page, int size) {
        Specification<CourierAssignment> filter = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                where.add(cb.equal(root.get("type"), type));
            }
            if (courierId != null) {
                where.add(cb.equal(root.get("courierId"), courierId));
            }
            if (branch != null) {
                where.add(cb.or(cb.equal(root.get("originBranch"), branch),
                        cb.equal(root.get("destinationBranch"), branch)));
            }
            if (scheduledFor != null) {
                where.add(cb.equal(root.get("scheduledFor"), scheduledFor));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest request = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<CourierAssignment> found = assignments.findAll(filter, request);
        List<AssignmentSummaryResponse> rows = views.summaries(found.getContent());
        return new PageResponse<>(rows, found.getNumber(), found.getSize(), found.getTotalElements(),
                found.getTotalPages());
    }

    /** One task with its parcels. @throws NotFoundException NOT_FOUND for an unknown id */
    public AssignmentDetailResponse detail(UUID assignmentId) {
        return assignments.findById(assignmentId)
                .map(views::detail)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    /** Active couriers (at the branch, or everywhere when null) with their open work, busiest last. */
    public List<CourierWorkloadResponse> workload(District branch) {
        List<CourierSummary> couriers = branch == null
                ? identity.listActiveCouriers()
                : identity.listActiveCouriersByBranch(branch);
        if (couriers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = couriers.stream().map(CourierSummary::id).toList();
        Map<UUID, Long> openTasks = byCourier(assignments.countOpenByCourier(ids));
        Map<UUID, Long> openParcels = byCourier(assignments.countOpenItemsByCourier(ids));
        Map<UUID, Long> completed = byCourier(
                assignments.countCompletedSinceByCourier(ids, DispatchDay.startOfToday(clock)));
        return couriers.stream()
                .map(c -> new CourierWorkloadResponse(c.id(), c.fullName(), c.phone(), c.branch(), c.vehicleType(),
                        openTasks.getOrDefault(c.id(), 0L), openParcels.getOrDefault(c.id(), 0L),
                        completed.getOrDefault(c.id(), 0L)))
                .sorted(Comparator.comparingLong(CourierWorkloadResponse::openParcels)
                        .thenComparing(CourierWorkloadResponse::fullName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** Today's figures for the branch. */
    public DispatchOverviewResponse overview(District branch) {
        Instant now = clock.instant();
        return new DispatchOverviewResponse(branch,
                queue(branch, QueueKind.PICKUP).size(),
                queue(branch, QueueKind.AT_BRANCH).size(),
                assignments.countByStatusAndBranch(AssignmentStatus.ASSIGNED, branch),
                assignments.countByStatusAndBranch(AssignmentStatus.IN_PROGRESS, branch),
                assignments.countCompletedSinceAtBranch(branch, DispatchDay.startOfToday(clock)),
                items.countIssues(ISSUE_OUTCOMES, branch, now.minus(Duration.ofDays(7))),
                identity.listActiveCouriersByBranch(branch).size());
    }

    /**
     * Parcels in the branch's tasks that couriers couldn't collect, load or deliver in the last {@code days} days,
     * newest first (at most {@link #MAX_PAGE_SIZE}).
     */
    public List<DeliveryIssueResponse> issues(District branch, int days) {
        Instant since = clock.instant().minus(Duration.ofDays(days));
        List<AssignmentItem> found = items.findIssues(ISSUE_OUTCOMES, branch, since,
                PageRequest.of(0, MAX_PAGE_SIZE));
        if (found.isEmpty()) {
            return List.of();
        }
        List<UUID> parcelIds = found.stream().map(AssignmentItem::getParcelId).distinct().toList();
        Map<UUID, DeliverySnapshot> parcels = views.parcels(parcelIds);
        Set<UUID> open = items.findOpenParcelIds(parcelIds);
        Map<UUID, CourierAssignment> byItem = found.stream().collect(Collectors.toMap(AssignmentItem::getId,
                AssignmentItem::assignment, (a, b) -> a));
        Map<UUID, CourierRef> couriers = views.couriers(
                byItem.values().stream().map(CourierAssignment::getCourierId).collect(Collectors.toSet()));
        return found.stream().map(item -> {
            CourierAssignment assignment = byItem.get(item.getId());
            DeliverySnapshot parcel = parcels.get(item.getParcelId());
            boolean waiting = parcel != null && !open.contains(parcel.id())
                    && (parcel.status() == ParcelStatus.CREATED || parcel.status() == ParcelStatus.AT_BRANCH);
            return new DeliveryIssueResponse(assignment.getId(), assignment.getType(), item.getParcelId(),
                    parcel == null ? null : parcel.trackingNumber(), parcel == null ? null : parcel.status(),
                    item.getOutcome(), item.getProblem(), item.getFailureReason(), item.getNote(),
                    couriers.get(assignment.getCourierId()), item.getUpdatedAt(), waiting);
        }).toList();
    }

    /**
     * CREATED parcels picked up in the district, oldest first, from the pickup queue. A row whose parcel has moved
     * on (its event not handled yet) is skipped, so the queue never offers a parcel that can't be picked up.
     */
    private List<DeliverySnapshot> waitingForPickup(District district) {
        List<PickupQueueEntry> entries = pickupQueue.findByPickupDistrictOrderByCreatedAtAsc(district);
        Map<UUID, DeliverySnapshot> parcels = views.parcels(entries.stream().map(PickupQueueEntry::getParcelId)
                .toList());
        return entries.stream()
                .map(entry -> parcels.get(entry.getParcelId()))
                .filter(parcel -> parcel != null && parcel.status() == ParcelStatus.CREATED
                        && parcel.pickup().district() == district)
                .toList();
    }

    /** AT_BRANCH parcels at the branch, oldest status change first (delivery's order). */
    private List<DeliverySnapshot> waitingAt(District branch) {
        return deliveries.findAtBranch(branch);
    }

    private static Map<UUID, Long> byCourier(List<CourierCount> counts) {
        return counts.stream().collect(Collectors.toMap(CourierCount::getCourierId, CourierCount::getTotal,
                Long::sum));
    }
}
