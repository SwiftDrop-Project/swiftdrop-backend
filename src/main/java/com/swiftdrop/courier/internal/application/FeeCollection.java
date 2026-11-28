package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.dispatch.AssignmentType;

/**
 * Where a courier collects a parcel's delivery fee in cash: the one rule the courier API shows
 * ({@code deliveryFee.collectOn}) and checks ({@code feeCollected} on a status change).
 *
 * <ul>
 *   <li>AT_PICKUP: from the sender, in a PICKUP task, with the PICKED_UP change;</li>
 *   <li>AT_DROP_OFF: from the recipient, in a DELIVERY task, with the DELIVERED change;</li>
 *   <li>never for ONLINE, for a parcel booked before payment methods, or once the fee is paid; never in a transfer, a
 *   handover, a failed delivery or a return to the branch.</li>
 * </ul>
 *
 * <p>Proposed business rule (for team review): when the fee is collected at a step, that step's successful change
 * needs the courier's confirmation ({@code feeCollected: true}). A sender who doesn't pay is "Unable to Collect"; a
 * recipient who doesn't pay is a failed delivery: the fee stays unpaid.
 */
public final class FeeCollection {

    private FeeCollection() {
    }

    /**
     * The status change that must confirm collection of the fee in this step (PICKED_UP or DELIVERED), or null when
     * nothing is collected here: the fee isn't a cash fee still awaiting collection, the task isn't the one that
     * collects it, or the step isn't open at that change.
     */
    public static ParcelStatus collectOn(DeliverySnapshot parcel, CourierTask task) {
        if (parcel.feePaymentStatus() != FeePaymentStatus.AWAITING_CASH_COLLECTION) {
            return null;
        }
        ParcelStatus target;
        AssignmentType taskType;
        if (parcel.paymentMethod() == PaymentMethod.AT_PICKUP) {
            target = ParcelStatus.PICKED_UP;
            taskType = AssignmentType.PICKUP;
        } else if (parcel.paymentMethod() == PaymentMethod.AT_DROP_OFF) {
            target = ParcelStatus.DELIVERED;
            taskType = AssignmentType.DELIVERY;
        } else {
            return null;
        }
        return task.type() == taskType && task.stepStatuses().contains(target) ? target : null;
    }
}
