package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment of a parcel's delivery fee (delivery.fee_payments): at most one per parcel. Written once, never changed.
 * The amount and currency are the parcel's own fee, copied when the payment is recorded. No personal data.
 */
@Entity
@Table(name = "fee_payments", schema = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeePaymentRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeePaymentSource source;

    @Column(name = "collected_by")
    private UUID collectedBy;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Transient
    private boolean isNew = true;

    /** A courier collected the parcel's fee in cash, in this task, now: the parcel's own amount and currency. */
    public static FeePaymentRecord cashCollected(UUID id, Parcel parcel, UUID courierId, UUID assignmentId,
                                                 Instant collectedAt) {
        FeePaymentRecord record = new FeePaymentRecord();
        record.id = id;
        record.parcelId = parcel.getId();
        record.amount = parcel.getFeeAmount();
        record.currency = parcel.getCurrency();
        record.paymentMethod = parcel.getPaymentMethod();
        record.source = FeePaymentSource.COURIER_CASH;
        record.collectedBy = courierId;
        record.assignmentId = assignmentId;
        record.collectedAt = collectedAt;
        return record;
    }

    /** The public view other modules get. */
    public FeePayment toPayment() {
        return new FeePayment(amount, currency, paymentMethod, source, collectedBy, assignmentId, collectedAt);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
