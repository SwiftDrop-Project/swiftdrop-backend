
package com.swiftdrop.notifications.internal.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "parcel_event_progress", schema = "notifications")
public class ParcelEventProgress {

    @Id
    @Column(name = "parcel_id")
    private UUID parcelId;

    @Column(name = "last_processed_version", nullable = false)
    private long lastProcessedVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParcelEventProgress() {
    }

    public ParcelEventProgress(UUID parcelId, long version) {
        this.parcelId = parcelId;
        this.lastProcessedVersion = version;
        this.updatedAt = Instant.now();
    }

    public long getLastProcessedVersion() {
        return lastProcessedVersion;
    }

    public void updateVersion(long version) {
        this.lastProcessedVersion = version;
        this.updatedAt = Instant.now();
    }
}
