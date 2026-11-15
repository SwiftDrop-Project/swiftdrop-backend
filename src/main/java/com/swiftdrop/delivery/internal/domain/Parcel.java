package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A parcel (delivery.parcels), with the rules for assigning a courier and changing status. customerId, merchantId
 * and assignedCourierId are identity user ids, stored as plain UUIDs (no foreign keys).
 * Personal data (contacts, instructions, proof name): no toString on purpose.
 */
@Entity
@Table(name = "parcels", schema = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Parcel {

    @Id
    private UUID id;

    @Column(name = "tracking_number", nullable = false, length = 20)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParcelStatus status;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "merchant_order_ref", length = 64)
    private String merchantOrderRef;

    @Embedded
    @AttributeOverride(name = "name", column = @Column(name = "pickup_name", nullable = false, length = 100))
    @AttributeOverride(name = "phone", column = @Column(name = "pickup_phone", nullable = false, length = 12))
    @AttributeOverride(name = "addressLine",
            column = @Column(name = "pickup_address_line", nullable = false, length = 200))
    @AttributeOverride(name = "city", column = @Column(name = "pickup_city", nullable = false, length = 80))
    @AttributeOverride(name = "district", column = @Column(name = "pickup_district", nullable = false, length = 30))
    private ParcelContact pickup;

    @Embedded
    @AttributeOverride(name = "name", column = @Column(name = "recipient_name", nullable = false, length = 100))
    @AttributeOverride(name = "phone", column = @Column(name = "recipient_phone", nullable = false, length = 12))
    @AttributeOverride(name = "addressLine",
            column = @Column(name = "recipient_address_line", nullable = false, length = 200))
    @AttributeOverride(name = "city", column = @Column(name = "recipient_city", nullable = false, length = 80))
    @AttributeOverride(name = "district",
            column = @Column(name = "recipient_district", nullable = false, length = 30))
    private ParcelContact recipient;

    @Column(name = "weight_kg", nullable = false, precision = 6, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "cod_amount", precision = 10, scale = 2)
    private BigDecimal codAmount;

    @Column(length = 500)
    private String instructions;

    @Column(name = "fee_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal feeAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Packed measurements in cm; all three or none (parcels booked before sizes have none). */
    @Column(name = "length_cm")
    private Integer lengthCm;

    @Column(name = "width_cm")
    private Integer widthCm;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Column(name = "keep_upright", nullable = false)
    private boolean keepUpright;

    /** The local legs' vehicle the fee was worked out with (a pricing Vehicle name), or null. */
    @Column(length = 20)
    private String vehicle;

    @Column(name = "billable_weight_kg")
    private Integer billableWeightKg;

    /** The pricing rate card of the fee, so the price can be worked out again; null for the old flat fee. */
    @Column(name = "rate_card_version")
    private Integer rateCardVersion;

    /** The fee lines as JSON. Hibernate stores the text in the jsonb column as it is. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fee_breakdown")
    private String feeBreakdown;

    @Column(name = "assigned_courier_id")
    private UUID assignedCourierId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "proof_recipient_name", length = 100)
    private String proofRecipientName;

    @Column(name = "proof_photo_id")
    private UUID proofPhotoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    /** The branch the parcel is at: set only while AT_BRANCH. */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_branch", length = 30)
    private District currentBranch;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Version
    private Long version;

    /** A new parcel with only a fee amount (no pricing details), as before pricing was connected. */
    public static Parcel create(UUID id, String trackingNumber, CreateDeliveryCommand command,
                                BigDecimal feeAmount, String currency, Instant now) {
        return create(id, trackingNumber, command, ParcelPrice.flat(feeAmount, currency), now);
    }

    /**
     * A new parcel in CREATED. The fee comes from pricing, never from the caller, and is kept with how it was worked
     * out; the tracking number comes from TrackingNumberGenerator.
     */
    public static Parcel create(UUID id, String trackingNumber, CreateDeliveryCommand command, ParcelPrice price,
                                Instant now) {
        Parcel parcel = new Parcel();
        parcel.id = id;
        parcel.trackingNumber = trackingNumber;
        parcel.status = ParcelStatus.CREATED;
        parcel.customerId = command.customerId();
        parcel.merchantId = command.merchantId();
        parcel.merchantOrderRef = command.merchantOrderRef();
        parcel.pickup = ParcelContact.from(command.pickup());
        parcel.recipient = ParcelContact.from(command.recipient());
        parcel.weightKg = command.weightKg();
        parcel.codAmount = command.codAmount();
        parcel.instructions = command.instructions();
        if (command.size() != null) {
            parcel.lengthCm = command.size().lengthCm();
            parcel.widthCm = command.size().widthCm();
            parcel.heightCm = command.size().heightCm();
            parcel.keepUpright = command.size().keepUpright();
        }
        parcel.feeAmount = price.amount();
        parcel.currency = price.currency();
        parcel.vehicle = price.vehicle();
        parcel.billableWeightKg = price.billableWeightKg();
        parcel.rateCardVersion = price.rateCardVersion();
        parcel.feeBreakdown = price.breakdownJson();
        parcel.createdAt = now;
        parcel.updatedAt = now;
        parcel.statusChangedAt = now;
        return parcel;
    }

    /**
     * Assigns a courier, or replaces the one assigned. Only while the parcel is CREATED: once it is picked up,
     * the courier stays. Checking that courierId is an active courier is the caller's job (dispatch).
     *
     * @throws BusinessRuleException PARCEL_NOT_ASSIGNABLE when the parcel is not CREATED any more
     */
    public void assignCourier(UUID courierId, Instant now) {
        Objects.requireNonNull(courierId, "courierId");
        if (status != ParcelStatus.CREATED) {
            throw new BusinessRuleException(ErrorCode.PARCEL_NOT_ASSIGNABLE);
        }
        this.assignedCourierId = courierId;
        this.assignedAt = now;
        this.updatedAt = now;
    }

    /**
     * Moves the parcel to {@code command.toStatus()} and keeps the failure reason (FAILED), the branch (AT_BRANCH) or
     * the proof of delivery (DELIVERED). The branch is cleared on every other status, and the failure reason when the
     * parcel leaves FAILED (the history keeps it), so the table's CHECK constraints hold. Who may make the change,
     * and which fields each status needs, are checked by the caller; ChangeStatusCommand checks the branch.
     *
     * @return the status before the change, for the history row and the event
     * @throws BusinessRuleException INVALID_STATUS_TRANSITION when the current status cannot move there
     */
    public ParcelStatus changeStatus(ChangeStatusCommand command, Instant now) {
        ParcelStatus from = this.status;
        if (!from.canMoveTo(command.toStatus())) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = command.toStatus();
        this.failureReason = this.status == ParcelStatus.FAILED ? command.failureReason() : null;
        this.currentBranch = this.status == ParcelStatus.AT_BRANCH ? command.branch() : null;
        if (this.status == ParcelStatus.DELIVERED) {
            this.proofRecipientName = command.recipientName();
            this.proofPhotoId = command.proofPhotoId();
        }
        this.statusChangedAt = now;
        this.updatedAt = now;
        return from;
    }

    /**
     * The read-only copy that other modules get. A parcel that was never saved has no version yet: it is 0, the
     * value the table gets.
     */
    public DeliverySnapshot toSnapshot() {
        return new DeliverySnapshot(id, trackingNumber, status, customerId, merchantId, pickup.toContact(),
                recipient.toContact(), weightKg, codAmount, instructions, assignedCourierId, assignedAt, createdAt,
                updatedAt, proofRecipientName, proofPhotoId != null, failureReason, merchantOrderRef, feeAmount,
                currency, version == null ? 0L : version, currentBranch, size(), vehicle, billableWeightKg,
                rateCardVersion);
    }

    /** The measurements, or null for a parcel booked before sizes. */
    private ParcelSize size() {
        return lengthCm == null ? null : new ParcelSize(lengthCm, widthCm, heightCm, keepUpright);
    }
}
