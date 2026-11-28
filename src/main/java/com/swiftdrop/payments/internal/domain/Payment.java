package com.swiftdrop.payments.internal.domain;

import com.swiftdrop.delivery.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment record of one customer delivery (payments.payments). Rows are created by PaymentRepository's insert
 * (one per parcel, safe when two requests make it at once) and changed only through the methods here.
 */
@Entity
@Table(name = "payments", schema = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "tracking_number", nullable = false, length = 20)
    private String trackingNumber;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** A new record as the repository's insert makes it (also for tests). */
    public Payment(UUID id, UUID parcelId, UUID customerId, String trackingNumber, Instant bookedAt,
                   PaymentMethod method, BigDecimal amount, String currency, PaymentStatus status) {
        this.id = id;
        this.parcelId = parcelId;
        this.customerId = customerId;
        this.trackingNumber = trackingNumber;
        this.bookedAt = bookedAt;
        this.method = method;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    /** Paid by mock card (only an online payment that is due). */
    public boolean isPaidByCard() {
        return cardLast4 != null;
    }

    /**
     * Follows the parcel: its fee, method and the status they give. A cash payment that becomes paid gets
     * {@code collectedAt} (when the courier took the cash) as its paid date. A card payment is never undone.
     */
    public void follow(PaymentMethod method, BigDecimal amount, String currency, PaymentStatus status,
                       Instant collectedAt) {
        this.method = method;
        if (!isPaidByCard()) {
            this.amount = amount;
            this.currency = currency;
        }
        if (status != this.status) {
            this.status = status;
            this.paidAt = status == PaymentStatus.PAID ? collectedAt : null;
        }
    }

    /** Records a mock card payment: only the brand and the last 4 digits are kept. */
    public void payByCard(String brand, String last4, Instant at) {
        this.status = PaymentStatus.PAID;
        this.cardBrand = brand;
        this.cardLast4 = last4;
        this.paidAt = at;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
