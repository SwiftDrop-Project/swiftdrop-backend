package com.swiftdrop.tracking.internal.domain;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One parcel's latest status in tracking.parcel_index, kept from delivery's snapshots. Read-only here: rows are
 * written by {@code ParcelIndexRepository.upsert}. No personal data.
 */
@Entity
@Table(name = "parcel_index", schema = "tracking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParcelIndexEntry {

    @Id
    @Column(name = "parcel_id")
    private UUID parcelId;

    @Column(name = "tracking_number", nullable = false, length = 20)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ParcelStatus status;

    @Column(name = "origin_city", length = 100)
    private String originCity;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_district", length = 30)
    private District originDistrict;

    @Column(name = "destination_city", length = 100)
    private String destinationCity;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_district", length = 30)
    private District destinationDistrict;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_branch", length = 30)
    private District currentBranch;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 40)
    private FailureReason failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "parcel_version", nullable = false)
    private long parcelVersion;
}
