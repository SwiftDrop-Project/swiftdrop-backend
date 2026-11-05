package com.swiftdrop.delivery;

import java.util.Objects;
import java.util.UUID;

/**
 * Input for moving a parcel to another status. {@code actorId} is the identity user id of whoever makes the change
 * and {@code actorRole} their role name, e.g. COURIER: the same values that go into {@link ParcelStatusChanged}.
 * The role is a String, not identity's Role, so that delivery does not depend on identity.
 *
 * <p>{@code note}, {@code failureReason} and {@code recipientName} (proof of delivery) are optional here. The
 * delivery module checks the business rules (the move is allowed, FAILED has a reason, a courier changes only
 * their own parcels) and answers with an API error when one is broken.
 */
public record ChangeStatusCommand(
        UUID parcelId,
        ParcelStatus toStatus,
        UUID actorId,
        String actorRole,
        String note,
        String failureReason,
        String recipientName) {

    public ChangeStatusCommand {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorRole, "actorRole");
    }
}
