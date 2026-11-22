package com.swiftdrop.dispatch.internal.domain;

import com.swiftdrop.shared.reference.District;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A parcel waiting to be picked up (dispatch.pickup_queue), kept from delivery's ParcelStatusChanged events. Holds no
 * personal data; the parcel itself is read from delivery when the queue is shown.
 */
@Entity
@Table(name = "pickup_queue", schema = "dispatch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickupQueueEntry {

    @Id
    @Column(name = "parcel_id")
    private UUID parcelId;

    @Column(name = "tracking_number", nullable = false, length = 20)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "pickup_district", nullable = false, length = 30)
    private District pickupDistrict;

    @Column(name = "parcel_version", nullable = false)
    private long parcelVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PickupQueueEntry(UUID parcelId, String trackingNumber, District pickupDistrict, long parcelVersion,
                            Instant createdAt) {
        this.parcelId = parcelId;
        this.trackingNumber = trackingNumber;
        this.pickupDistrict = pickupDistrict;
        this.parcelVersion = parcelVersion;
        this.createdAt = createdAt;
    }
}
