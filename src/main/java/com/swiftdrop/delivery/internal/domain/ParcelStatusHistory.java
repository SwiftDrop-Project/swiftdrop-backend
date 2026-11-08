package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;
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
 * One status change of a parcel (delivery.parcel_status_history), the first one (CREATED) included. Rows are only
 * inserted, never changed. The note is free text that may hold personal data: no toString on purpose.
 */
@Entity
@Table(name = "parcel_status_history", schema = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParcelStatusHistory implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ParcelStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ParcelStatus toStatus;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 20)
    private String actorRole;

    @Column(length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    /** Only for AT_BRANCH: the branch the parcel arrived at. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private District branch;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Transient
    private boolean isNew = true;

    /**
     * The first row of a new parcel: CREATED, made by whoever booked it (a customer, or a merchant for an API
     * order). Role names are plain strings, because delivery never depends on identity.
     */
    public static ParcelStatusHistory firstEntry(UUID id, Parcel parcel) {
        ParcelStatusHistory entry = new ParcelStatusHistory();
        entry.id = id;
        entry.parcelId = parcel.getId();
        entry.toStatus = ParcelStatus.CREATED;
        if (parcel.getCustomerId() != null) {
            entry.actorId = parcel.getCustomerId();
            entry.actorRole = "CUSTOMER";
        } else {
            entry.actorId = parcel.getMerchantId();
            entry.actorRole = "MERCHANT";
        }
        entry.occurredAt = parcel.getCreatedAt();
        return entry;
    }

    /**
     * A later change, after Parcel.changeStatus: {@code from} is the status that it returned. The failure reason
     * is kept only for FAILED and the branch only for AT_BRANCH, like on the parcel.
     */
    public static ParcelStatusHistory forChange(UUID id, ParcelStatus from, ChangeStatusCommand command, Instant at) {
        ParcelStatusHistory entry = new ParcelStatusHistory();
        entry.id = id;
        entry.parcelId = command.parcelId();
        entry.fromStatus = from;
        entry.toStatus = command.toStatus();
        entry.actorId = command.actorId();
        entry.actorRole = command.actorRole();
        entry.note = command.note();
        entry.failureReason = command.toStatus() == ParcelStatus.FAILED ? command.failureReason() : null;
        entry.branch = command.toStatus() == ParcelStatus.AT_BRANCH ? command.branch() : null;
        entry.occurredAt = at;
        return entry;
    }

    /** The public view for DeliveryApi.history(). The from status and the actor id stay inside delivery. */
    public ParcelStatusEntry toEntry() {
        return new ParcelStatusEntry(toStatus, note, failureReason, actorRole, occurredAt, branch);
    }

    @Override
    public UUID getId() {
        return id;
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
