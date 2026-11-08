// TEMPORARY - delete when WP3 implements DeliveryApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.delivery.ProofPhoto;
import com.swiftdrop.delivery.ProofPhotoRef;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY in-memory {@link DeliveryApi} for courier demos, on only with {@code SWIFTDROP_FAKE_DELIVERY=true}.
 *
 * <p>Only the methods the courier screens use are real ({@code findById}, {@code findByTrackingNumber},
 * {@code findAllById}, {@code history}, {@code changeStatus}); they follow the {@code DeliveryApi} Javadoc so
 * behaviour stays the same when WP3's implementation replaces this. Every other method, including the deprecated
 * single-courier reads, throws {@link UnsupportedOperationException}. Nothing is written to the database and no
 * events are published. Changes made inside a transaction are undone when it rolls back ({@link FakeTransactions}).
 *
 * <p>{@code @Primary}: if a real implementation exists while the flag is still on, this one wins and
 * {@link FakeDeliverySeeder} logs a warning, instead of the app failing on two beans.
 */
@Component
@Primary
@ConditionalOnProperty(name = "swiftdrop.dev.fake-delivery", havingValue = "true")
class FakeDeliveryApi implements DeliveryApi {

    static final String UNSUPPORTED = "FakeDeliveryApi: not supported, wait for WP3";

    private final Map<UUID, DeliverySnapshot> parcels = new ConcurrentHashMap<>();
    /** Newest first; each list is immutable and replaced on change, so reads need no lock. */
    private final Map<UUID, List<ParcelStatusEntry>> histories = new ConcurrentHashMap<>();
    private final Clock clock;

    FakeDeliveryApi() {
        this(Clock.systemUTC());
    }

    FakeDeliveryApi(Clock clock) {
        this.clock = clock;
    }

    /** Adds a parcel and its history (newest first). Used by the seeder and tests. */
    void put(DeliverySnapshot parcel, List<ParcelStatusEntry> historyNewestFirst) {
        histories.put(parcel.id(), List.copyOf(historyNewestFirst));
        parcels.put(parcel.id(), parcel);
    }

    boolean isEmpty() {
        return parcels.isEmpty();
    }

    // ---- The courier methods (real) ----

    @Override
    public Optional<DeliverySnapshot> findById(UUID parcelId) {
        return Optional.ofNullable(parcels.get(parcelId));
    }

    @Override
    public Optional<DeliverySnapshot> findByTrackingNumber(String trackingNumber) {
        if (trackingNumber == null) {
            return Optional.empty();
        }
        String normalized = trackingNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return parcels.values().stream()
                .filter(parcel -> parcel.trackingNumber().equals(normalized))
                .findFirst();
    }

    @Override
    public List<DeliverySnapshot> findAllById(Collection<UUID> parcelIds) {
        return parcelIds.stream().distinct().map(parcels::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<ParcelStatusEntry> history(UUID parcelId) {
        return histories.getOrDefault(parcelId, List.of());
    }

    @Override
    public synchronized DeliverySnapshot changeStatus(ChangeStatusCommand command) {
        DeliverySnapshot parcel = parcels.get(command.parcelId());
        if (parcel == null) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        // Multi-branch: the caller checked the courier's assignment; delivery checks only the role.
        String role = command.actorRole();
        if (!"COURIER".equals(role) && !"ADMIN".equals(role)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN);
        }
        ParcelStatus target = command.toStatus();
        if (!parcel.status().canMoveTo(target)) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        if (command.proofPhotoId() != null) {
            // The fake stores no photos (they come with PR2b), so no photo id can belong to this parcel.
            throw new FieldValidationException("proofPhotoId", "Add the photo again.");
        }

        Instant now = clock.instant();
        boolean delivered = target == ParcelStatus.DELIVERED;
        boolean failed = target == ParcelStatus.FAILED;
        DeliverySnapshot updated = new DeliverySnapshot(parcel.id(), parcel.trackingNumber(), target,
                parcel.customerId(), parcel.merchantId(), parcel.pickup(), parcel.recipient(), parcel.weightKg(),
                parcel.codAmount(), parcel.instructions(), parcel.assignedCourierId(), parcel.assignedAt(),
                parcel.createdAt(), now,
                delivered ? command.recipientName() : null,
                false,
                failed ? command.failureReason() : null,
                parcel.merchantOrderRef(), parcel.feeAmount(), parcel.currency(), parcel.version() + 1,
                target == ParcelStatus.AT_BRANCH ? command.branch() : null);

        List<ParcelStatusEntry> previousHistory = history(parcel.id());
        List<ParcelStatusEntry> history = new ArrayList<>();
        history.add(new ParcelStatusEntry(target, command.note(), failed ? command.failureReason() : null, role, now,
                command.branch()));
        history.addAll(previousHistory);
        histories.put(parcel.id(), List.copyOf(history));
        parcels.put(parcel.id(), updated);
        FakeTransactions.onRollback(this, () -> restore(parcel, previousHistory));
        return updated;
    }

    private synchronized void restore(DeliverySnapshot parcel, List<ParcelStatusEntry> history) {
        histories.put(parcel.id(), history);
        parcels.put(parcel.id(), parcel);
    }

    // ---- Not supported by the fake ----

    @Override
    public DeliverySnapshot create(CreateDeliveryCommand command) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public DeliverySnapshot assignCourier(UUID parcelId, UUID courierId) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public List<DeliverySnapshot> findForCourier(UUID courierId, Instant finishedSince) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public Page<DeliverySnapshot> findFinishedForCourier(UUID courierId, String trackingNumber, Pageable pageable) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public Page<DeliverySnapshot> findByCustomer(UUID customerId, ParcelStatus status, Pageable pageable) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public Page<DeliverySnapshot> findByMerchant(UUID merchantId, ParcelStatus status, Pageable pageable) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public List<DeliverySnapshot> findAtBranch(District branch) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public List<DeliverySnapshot> findUnassigned() {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public Map<UUID, Long> countActiveByCourier() {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public ProofPhotoRef storeProofPhoto(UUID parcelId, UUID uploadedBy, String contentType, byte[] content) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public Optional<ProofPhoto> findProofPhoto(UUID parcelId) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    @Override
    public DeliveryStats stats(LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException(UNSUPPORTED);
    }
}
