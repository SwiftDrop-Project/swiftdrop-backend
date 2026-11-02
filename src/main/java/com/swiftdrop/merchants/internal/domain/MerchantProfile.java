package com.swiftdrop.merchants.internal.domain;

import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
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

import java.time.Instant;
import java.util.UUID;

/**
 * Business details of a MERCHANT user. Its id is the identity user id (one profile per merchant), stored as a plain
 * UUID: no foreign key and no join across schemas.
 */
@Entity
@Table(name = "merchant_profiles", schema = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantProfile implements Persistable<UUID> {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "business_name", nullable = false, length = 120)
    private String businessName;

    @Column(name = "business_reg_no", length = 30)
    private String businessRegNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "parcels_per_month", nullable = false, length = 20)
    private ParcelsPerMonth parcelsPerMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "pickup_district", nullable = false, length = 30)
    private District pickupDistrict;

    @Column(nullable = false, length = 80)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Id of the ADMIN who approved or rejected the application. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /** The id is assigned (not generated), so tell Spring Data when to INSERT instead of MERGE. */
    @Transient
    private boolean isNew = true;

    public MerchantProfile(UUID userId, String businessName, String businessRegNo, ParcelsPerMonth parcelsPerMonth,
                           District pickupDistrict, String city) {
        this.userId = userId;
        this.businessName = businessName;
        this.businessRegNo = businessRegNo;
        this.parcelsPerMonth = parcelsPerMonth;
        this.pickupDistrict = pickupDistrict;
        this.city = city;
        this.approvalStatus = ApprovalStatus.PENDING;
    }

    public void approve(UUID adminId) {
        review(ApprovalStatus.APPROVED, adminId);
    }

    public void reject(UUID adminId) {
        review(ApprovalStatus.REJECTED, adminId);
    }

    private void review(ApprovalStatus status, UUID adminId) {
        this.approvalStatus = status;
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return userId;
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
