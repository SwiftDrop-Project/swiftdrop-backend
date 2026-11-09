package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.delivery.ProofPhoto;
import com.swiftdrop.delivery.ProofPhotoRef;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ParcelStatusHistory;
import com.swiftdrop.delivery.internal.domain.TrackingNumberGenerator;
import com.swiftdrop.delivery.internal.persistence.DailyTotal;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.delivery.internal.persistence.ParcelStatusHistoryRepository;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The delivery module's implementation of {@link DeliveryApi}; other modules only see the interface.
 *
 * <p>Built feature by feature: {@link #create}, the reads, {@link #changeStatus} and {@link #stats} work now. Proof
 * photos wait for WP9, and the deprecated single-courier methods are not built. Those answer
 * {@code 503 SERVICE_UNAVAILABLE} ("This isn't available yet"), the same answer the courier module gives when no
 * DeliveryApi exists.
 */
@Service
class DeliveryService implements DeliveryApi {

    /** Tries for a free tracking number. A clash is rare (6 random digits), so five in a row means a bug. */
    static final int TRACKING_NUMBER_TRIES = 5;

    /** DELIVERED and FAILED, taken from ParcelStatus.isFinished() so the rule lives in one place. */
    static final Set<ParcelStatus> FINISHED = Arrays.stream(ParcelStatus.values())
            .filter(ParcelStatus::isFinished)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(ParcelStatus.class)));

    /** The roles that may change a parcel's status. Plain names, because delivery never depends on identity. */
    static final Set<String> STATUS_CHANGE_ROLES = Set.of("COURIER", "ADMIN");

    /** The largest page findByCustomer and findByMerchant return (DeliveryApi: "capped at 100"). */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * UTC in whole microseconds, the precision PostgreSQL stores. Times kept in memory (snapshots, events) then match
     * what is read back from the database later.
     */
    static final Clock DATABASE_CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    /** The longest range stats() accepts (DeliveryApi: "at most 366 days", a leap year). */
    static final int MAX_STATS_DAYS = 366;

    /** Report days are Sri Lanka days. */
    static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    private final ParcelRepository parcels;
    private final ParcelStatusHistoryRepository statusHistory;
    private final OutboxWriter outbox;
    private final FeeQuoter feeQuoter;
    private final TrackingNumberGenerator trackingNumbers;
    private final Clock clock;

    @Autowired
    DeliveryService(ParcelRepository parcels, ParcelStatusHistoryRepository statusHistory, OutboxWriter outbox,
                    FeeQuoter feeQuoter, TrackingNumberGenerator trackingNumbers) {
        this(parcels, statusHistory, outbox, feeQuoter, trackingNumbers, DATABASE_CLOCK);
    }

    /** For tests: a fixed clock. */
    DeliveryService(ParcelRepository parcels, ParcelStatusHistoryRepository statusHistory, OutboxWriter outbox,
                    FeeQuoter feeQuoter, TrackingNumberGenerator trackingNumbers, Clock clock) {
        this.parcels = parcels;
        this.statusHistory = statusHistory;
        this.outbox = outbox;
        this.feeQuoter = feeQuoter;
        this.trackingNumbers = trackingNumbers;
        this.clock = clock;
    }

    /**
     * All in one transaction: a merchant's retry returns the first parcel; otherwise the fee, a free tracking number,
     * then the parcel, its first history row and the CREATED event in the outbox.
     */
    @Override
    @Transactional
    public DeliverySnapshot create(CreateDeliveryCommand command) {
        if (command.merchantId() != null && command.merchantOrderRef() != null) {
            Optional<Parcel> earlier = parcels.findByMerchantIdAndMerchantOrderRef(command.merchantId(),
                    command.merchantOrderRef());
            if (earlier.isPresent()) {
                return earlier.get().toSnapshot();
            }
        }
        Instant now = clock.instant();
        FeeQuoter.Fee fee = feeQuoter.quote(command);
        Parcel parcel = Parcel.create(UUID.randomUUID(), newTrackingNumber(now), command, fee.amount(),
                fee.currency(), now);
        parcels.save(parcel);
        ParcelStatusHistory first = ParcelStatusHistory.firstEntry(UUID.randomUUID(), parcel);
        statusHistory.save(first);
        DeliverySnapshot snapshot = parcel.toSnapshot();
        outbox.write(new ParcelStatusChanged(UUID.randomUUID(), parcel.getId(), snapshot.version(),
                parcel.getTrackingNumber(), parcel.getCustomerId(), parcel.getMerchantId(), null,
                ParcelStatus.CREATED, first.getActorId(), first.getActorRole(), null, null, now, null));
        return snapshot;
    }

    private String newTrackingNumber(Instant now) {
        for (int attempt = 0; attempt < TRACKING_NUMBER_TRIES; attempt++) {
            String number = trackingNumbers.next(now);
            if (!parcels.existsByTrackingNumber(number)) {
                return number;
            }
        }
        throw new IllegalStateException("No free tracking number after " + TRACKING_NUMBER_TRIES + " tries");
    }

    // ---- Reading parcels ----

    @Override
    @Transactional(readOnly = true)
    public Optional<DeliverySnapshot> findById(UUID parcelId) {
        return parcels.findById(parcelId).map(Parcel::toSnapshot);
    }

    /**
     * For tracking (WP8) and the courier's history search (WP9). The number is normalised the way it is stored, so
     * " sd 2611 004817 " finds SD2611004817. A missing or blank number finds nothing, without asking the database.
     * The snapshot holds personal data (phones, addresses): a public page must show only what it needs.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<DeliverySnapshot> findByTrackingNumber(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return Optional.empty();
        }
        return parcels.findByTrackingNumber(TrackingNumberGenerator.normalise(trackingNumber))
                .map(Parcel::toSnapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParcelStatusEntry> history(UUID parcelId) {
        return statusHistory.findByParcelIdOrderByOccurredAtDesc(parcelId).stream()
                .map(ParcelStatusHistory::toEntry)
                .toList();
    }

    /** Unknown ids are left out, so the result may be shorter than the input. Any order. */
    @Override
    @Transactional(readOnly = true)
    public List<DeliverySnapshot> findAllById(Collection<UUID> parcelIds) {
        return parcels.findAllById(parcelIds).stream()
                .map(Parcel::toSnapshot)
                .toList();
    }

    /** For dispatch (WP8): the parcels waiting at this branch, oldest status change first. */
    @Override
    @Transactional(readOnly = true)
    public List<DeliverySnapshot> findAtBranch(District branch) {
        return parcels.findWaitingAtBranch(branch).stream()
                .map(Parcel::toSnapshot)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliverySnapshot> findForCourier(UUID courierId, Instant finishedSince) {
        return parcels.findForCourier(courierId, FINISHED, finishedSince).stream()
                .map(Parcel::toSnapshot)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliverySnapshot> findFinishedForCourier(UUID courierId, String trackingNumber, Pageable pageable) {
        Page<Parcel> finished = trackingNumber == null
                ? parcels.findByAssignedCourierIdAndStatusInOrderByStatusChangedAtDesc(courierId, FINISHED, pageable)
                : parcels.findByAssignedCourierIdAndStatusInAndTrackingNumberOrderByStatusChangedAtDesc(
                        courierId, FINISHED, trackingNumber, pageable);
        return finished.map(Parcel::toSnapshot);
    }

    // ---- A customer's parcels and a merchant's orders ----

    /**
     * Newest first, optionally only one status, at most {@link #MAX_PAGE_SIZE} per page. A null customer id is a
     * caller's bug: the query would turn it into "customer_id IS NULL" and return every merchant order.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<DeliverySnapshot> findByCustomer(UUID customerId, ParcelStatus status, Pageable pageable) {
        Objects.requireNonNull(customerId, "customerId");
        Pageable page = cappedPage(pageable);
        Page<Parcel> found = status == null
                ? parcels.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId, page)
                : parcels.findByCustomerIdAndStatusOrderByCreatedAtDescIdDesc(customerId, status, page);
        return found.map(Parcel::toSnapshot);
    }

    /** The same rules as {@link #findByCustomer}; a null merchant id would return every customer's parcel. */
    @Override
    @Transactional(readOnly = true)
    public Page<DeliverySnapshot> findByMerchant(UUID merchantId, ParcelStatus status, Pageable pageable) {
        Objects.requireNonNull(merchantId, "merchantId");
        Pageable page = cappedPage(pageable);
        Page<Parcel> found = status == null
                ? parcels.findByMerchantIdOrderByCreatedAtDescIdDesc(merchantId, page)
                : parcels.findByMerchantIdAndStatusOrderByCreatedAtDescIdDesc(merchantId, status, page);
        return found.map(Parcel::toSnapshot);
    }

    /**
     * The caller's page number, at most MAX_PAGE_SIZE items, and no sort: the order is always newest first (it is in
     * the repository method names). "Unpaged" becomes the first page.
     */
    static Pageable cappedPage(Pageable requested) {
        if (requested.isUnpaged()) {
            return PageRequest.of(0, MAX_PAGE_SIZE);
        }
        return PageRequest.of(requested.getPageNumber(), Math.min(requested.getPageSize(), MAX_PAGE_SIZE));
    }

    // ---- Changing status (the courier module calls this) ----

    /**
     * Moves the parcel to the new status, in one transaction with its history row and the event in the outbox.
     * Checks in this order: the parcel exists (404), the role may change status (403), the current status may move
     * there (409), the proof photo (400). Which courier may change this parcel is checked by the caller.
     */
    @Override
    @Transactional
    public DeliverySnapshot changeStatus(ChangeStatusCommand command) {
        Parcel parcel = parcels.findById(command.parcelId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (!STATUS_CHANGE_ROLES.contains(command.actorRole())) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN);
        }
        Instant now = clock.instant();
        ParcelStatus from = parcel.changeStatus(command, now);
        if (command.proofPhotoId() != null) {
            // storeProofPhoto is not built yet, so no photo belongs to this parcel. The exception rolls the
            // transaction back: the change above is never saved.
            throw new FieldValidationException("proofPhotoId", "Add the photo again.");
        }
        try {
            // Flush now: the version goes up here, and the event must carry the new one.
            parcels.saveAndFlush(parcel);
        } catch (OptimisticLockingFailureException ex) {
            // Someone changed the parcel after we read it, so this move may no longer be allowed.
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        statusHistory.save(ParcelStatusHistory.forChange(UUID.randomUUID(), from, command, now));
        DeliverySnapshot snapshot = parcel.toSnapshot();
        outbox.write(new ParcelStatusChanged(UUID.randomUUID(), parcel.getId(), snapshot.version(),
                parcel.getTrackingNumber(), parcel.getCustomerId(), parcel.getMerchantId(), from, parcel.getStatus(),
                command.actorId(), command.actorRole(), command.note(),
                parcel.getFailureReason() == null ? null : parcel.getFailureReason().name(), now,
                parcel.getCurrentBranch()));
        return snapshot;
    }

    // ---- Reports (WP4) ----

    /**
     * Counts per Sri Lanka day, every day of the range included (0 when nothing happened). Created comes from the
     * parcels; delivered and failed from the status history, because a FAILED parcel goes back to a branch and may
     * fail again or be delivered later, so the parcel's current status would miss those days.
     *
     * @throws IllegalArgumentException when {@code from} is after {@code to} or the range is longer than
     *                                  {@link #MAX_STATS_DAYS} days (the reporting module checks the admin's dates)
     */
    @Override
    @Transactional(readOnly = true)
    public DeliveryStats stats(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from (" + from + ") is after to (" + to + ")");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_STATS_DAYS) {
            throw new IllegalArgumentException("The range has " + days + " days; at most " + MAX_STATS_DAYS);
        }
        Instant start = from.atStartOfDay(SRI_LANKA).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SRI_LANKA).toInstant();
        Map<LocalDate, Long> created = byDay(parcels.countCreatedPerDay(start, end));
        Map<LocalDate, Long> delivered = byDay(statusHistory.countReachedPerDay(ParcelStatus.DELIVERED.name(), start,
                end));
        Map<LocalDate, Long> failed = byDay(statusHistory.countReachedPerDay(ParcelStatus.FAILED.name(), start, end));
        List<DeliveryStats.DailyCount> perDay = from.datesUntil(to.plusDays(1))
                .map(day -> new DeliveryStats.DailyCount(day, created.getOrDefault(day, 0L),
                        delivered.getOrDefault(day, 0L), failed.getOrDefault(day, 0L)))
                .toList();
        return new DeliveryStats(from, to, perDay);
    }

    private static Map<LocalDate, Long> byDay(List<DailyTotal> rows) {
        return rows.stream().collect(Collectors.toMap(row -> LocalDate.parse(row.getDay()), DailyTotal::getTotal));
    }

    // ---- Not built yet: each feature replaces its methods below ----

    @Override
    public DeliverySnapshot assignCourier(UUID parcelId, UUID courierId) {
        throw notBuiltYet();
    }

    @Override
    public List<DeliverySnapshot> findUnassigned() {
        throw notBuiltYet();
    }

    @Override
    public Map<UUID, Long> countActiveByCourier() {
        throw notBuiltYet();
    }

    @Override
    public ProofPhotoRef storeProofPhoto(UUID parcelId, UUID uploadedBy, String contentType, byte[] content) {
        throw notBuiltYet();
    }

    @Override
    public Optional<ProofPhoto> findProofPhoto(UUID parcelId) {
        throw notBuiltYet();
    }

    /** 503 with "This isn't available yet. Please try again later.", shown as-is to the user. */
    private static ApiException notBuiltYet() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
