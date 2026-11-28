package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.RecordFeeCollectionCommand;
import com.swiftdrop.delivery.internal.domain.FeePaymentRecord;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.persistence.FeePaymentRepository;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Delivery fee payments (delivery.fee_payments, WP9 courier fee collection): reads a parcel's payment for its snapshot
 * and records a courier's cash collection. One payment per parcel, checked here and enforced by the database.
 */
@Component
class FeePayments {

    private final FeePaymentRepository payments;

    FeePayments(FeePaymentRepository payments) {
        this.payments = payments;
    }

    /** The parcel's payment, if any. */
    Optional<FeePayment> forParcel(UUID parcelId) {
        return payments.findByParcelId(parcelId).map(FeePaymentRecord::toPayment);
    }

    /** The payments of these parcels by parcel id, in one query; unpaid parcels are missing. */
    Map<UUID, FeePayment> forParcels(Collection<UUID> parcelIds) {
        if (parcelIds.isEmpty()) {
            return Map.of();
        }
        return payments.findByParcelIdIn(parcelIds).stream()
                .collect(Collectors.toMap(FeePaymentRecord::getParcelId, FeePaymentRecord::toPayment,
                        (first, second) -> first));
    }

    /**
     * Records the courier's cash collection of the parcel's fee, with the parcel's own amount and currency. Allowed
     * only for a cash fee at its own step: AT_PICKUP once the parcel is PICKED_UP, AT_DROP_OFF once it is DELIVERED
     * (the caller has just made that change in this transaction). Flushed at once, so a duplicate (two requests at the
     * same moment) fails here with FEE_ALREADY_COLLECTED, and the caller's transaction rolls back.
     */
    FeePayment recordCashCollection(Parcel parcel, RecordFeeCollectionCommand command, Instant now) {
        ParcelStatus collectedAt = collectionStatus(parcel.getPaymentMethod());
        if (collectedAt == null || parcel.getStatus() != collectedAt) {
            throw new BusinessRuleException(ErrorCode.FEE_COLLECTION_NOT_ALLOWED);
        }
        if (payments.existsByParcelId(parcel.getId())) {
            throw new ConflictException(ErrorCode.FEE_ALREADY_COLLECTED);
        }
        FeePaymentRecord record = FeePaymentRecord.cashCollected(UUID.randomUUID(), parcel, command.courierId(),
                command.assignmentId(), now);
        try {
            payments.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // uk_fee_payments_parcel_id: another request recorded it first.
            throw new ConflictException(ErrorCode.FEE_ALREADY_COLLECTED);
        }
        return record.toPayment();
    }

    /** The parcel status at which a cash fee is collected: PICKED_UP (AT_PICKUP), DELIVERED (AT_DROP_OFF), else null. */
    static ParcelStatus collectionStatus(PaymentMethod method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case AT_PICKUP -> ParcelStatus.PICKED_UP;
            case AT_DROP_OFF -> ParcelStatus.DELIVERED;
            case ONLINE -> null;
        };
    }
}
