package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;

/**
 * Proof of delivery: who received the parcel and whether the photo of the signed delivery slip is attached (it is
 * read with {@code GET /courier/deliveries/{parcelId}/proof}).
 *
 * <p>Task-scoped: only the entry of the DELIVERY step that delivered the parcel carries it. Every other entry (a
 * pickup, a failed or returned delivery, Unable to Collect, a cancelled task) gets null, also for a parcel that was
 * delivered later, so a courier who only collected or returned the parcel never learns the recipient's name or that a
 * signed slip exists. The image itself has its own check (only the courier who delivered it).
 */
public record ProofResponse(String recipientName, boolean hasPhoto) {

    /** The proof on the entry that delivered the parcel, otherwise null. */
    static ProofResponse of(DeliverySnapshot parcel, CourierTask task) {
        boolean deliveredInThisStep = task.type() == AssignmentType.DELIVERY
                && task.outcome() == ItemOutcome.DELIVERED
                && task.completed();
        return deliveredInThisStep && parcel.status() == ParcelStatus.DELIVERED
                ? new ProofResponse(parcel.proofRecipientName(), parcel.hasProofPhoto())
                : null;
    }
}
