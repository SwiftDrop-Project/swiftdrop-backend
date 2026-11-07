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
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.delivery.internal.persistence.ParcelStatusHistoryRepository;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The delivery module's implementation of {@link DeliveryApi}; other modules only see the interface.
 *
 * <p>Built feature by feature: {@link #create} works now. Until their feature is done, the other methods answer
 * {@code 503 SERVICE_UNAVAILABLE} ("This isn't available yet"), the same answer the courier module gives when no
 * DeliveryApi exists. So the bean already exists and modules that inject DeliveryApi can start.
 */
@Service
class DeliveryService implements DeliveryApi {

    /** Tries for a free tracking number. A clash is rare (6 random digits), so five in a row means a bug. */
    static final int TRACKING_NUMBER_TRIES = 5;

    private final ParcelRepository parcels;
    private final ParcelStatusHistoryRepository statusHistory;
    private final OutboxWriter outbox;
    private final FeeQuoter feeQuoter;
    private final TrackingNumberGenerator trackingNumbers;
    private final Clock clock;

    @Autowired
    DeliveryService(ParcelRepository parcels, ParcelStatusHistoryRepository statusHistory, OutboxWriter outbox,
                    FeeQuoter feeQuoter, TrackingNumberGenerator trackingNumbers) {
        this(parcels, statusHistory, outbox, feeQuoter, trackingNumbers, Clock.systemUTC());
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
                ParcelStatus.CREATED, first.getActorId(), first.getActorRole(), null, null, now));
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

    // ---- Not built yet: each feature replaces its methods below ----

    @Override
    public DeliverySnapshot assignCourier(UUID parcelId, UUID courierId) {
        throw notBuiltYet();
    }

    @Override
    public Optional<DeliverySnapshot> findById(UUID parcelId) {
        throw notBuiltYet();
    }

    @Override
    public Optional<DeliverySnapshot> findByTrackingNumber(String trackingNumber) {
        throw notBuiltYet();
    }

    @Override
    public Page<DeliverySnapshot> findByCustomer(UUID customerId, ParcelStatus status, Pageable pageable) {
        throw notBuiltYet();
    }

    @Override
    public Page<DeliverySnapshot> findByMerchant(UUID merchantId, ParcelStatus status, Pageable pageable) {
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
    public List<DeliverySnapshot> findForCourier(UUID courierId, Instant finishedSince) {
        throw notBuiltYet();
    }

    @Override
    public Page<DeliverySnapshot> findFinishedForCourier(UUID courierId, String trackingNumber, Pageable pageable) {
        throw notBuiltYet();
    }

    @Override
    public List<ParcelStatusEntry> history(UUID parcelId) {
        throw notBuiltYet();
    }

    @Override
    public DeliverySnapshot changeStatus(ChangeStatusCommand command) {
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

    @Override
    public DeliveryStats stats(LocalDate from, LocalDate to) {
        throw notBuiltYet();
    }

    /** 503 with "This isn't available yet. Please try again later.", shown as-is to the user. */
    private static ApiException notBuiltYet() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
