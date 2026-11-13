package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;

/**
 * Which parcels suit which task, and which task a parcel needs next (multi-branch workflow):
 * <ul>
 *   <li>PICKUP: the parcel is CREATED and its sender is in the destination branch's district</li>
 *   <li>TRANSFER: the parcel is AT_BRANCH at the origin branch</li>
 *   <li>DELIVERY: the parcel is AT_BRANCH at the origin branch, and that is its recipient's district</li>
 * </ul>
 * Parcels are moved directly to their recipient's district (no hub routing).
 */
final class ParcelRules {

    private ParcelRules() {
    }

    /** The next task for a parcel, with its branches. */
    record Leg(AssignmentType type, District origin, District destination) {
    }

    /**
     * The task the parcel needs next: PICKUP for a new parcel, DELIVERY when it is at its recipient's branch,
     * otherwise a TRANSFER to the recipient's district.
     *
     * @throws BusinessRuleException PARCEL_NOT_READY_FOR_TASK when the parcel is on its way (picked up, in transit,
     *                               out for delivery, failed and not yet returned) or delivered
     */
    static Leg nextLeg(DeliverySnapshot parcel) {
        if (parcel.status() == ParcelStatus.CREATED) {
            return new Leg(AssignmentType.PICKUP, null, parcel.pickup().district());
        }
        if (parcel.status() == ParcelStatus.AT_BRANCH && parcel.currentBranch() != null) {
            District branch = parcel.currentBranch();
            District recipientDistrict = parcel.recipient().district();
            return branch == recipientDistrict
                    ? new Leg(AssignmentType.DELIVERY, branch, null)
                    : new Leg(AssignmentType.TRANSFER, branch, recipientDistrict);
        }
        throw notReady(parcel, "is not waiting for a courier");
    }

    /**
     * Checks that the parcel suits a task of this type between these branches.
     *
     * @throws BusinessRuleException PARCEL_NOT_READY_FOR_TASK, naming the parcel and what doesn't fit
     */
    static void requireSuits(DeliverySnapshot parcel, AssignmentType type, District origin, District destination) {
        switch (type) {
            case PICKUP -> {
                if (parcel.status() != ParcelStatus.CREATED) {
                    throw notReady(parcel, "is not waiting for pickup");
                }
                if (parcel.pickup().district() != destination) {
                    throw notReady(parcel, "is picked up in another district");
                }
            }
            case TRANSFER -> {
                if (parcel.status() != ParcelStatus.AT_BRANCH || parcel.currentBranch() != origin) {
                    throw notReady(parcel, "is not at the origin branch");
                }
            }
            case DELIVERY -> {
                if (parcel.status() != ParcelStatus.AT_BRANCH || parcel.currentBranch() != origin) {
                    throw notReady(parcel, "is not at the origin branch");
                }
                if (parcel.recipient().district() != origin) {
                    throw notReady(parcel, "goes to another district, so it needs a transfer first");
                }
            }
        }
    }

    private static BusinessRuleException notReady(DeliverySnapshot parcel, String why) {
        return new BusinessRuleException(ErrorCode.PARCEL_NOT_READY_FOR_TASK,
                "Parcel " + parcel.trackingNumber() + " " + why + ". Refresh to see its latest status.");
    }
}
