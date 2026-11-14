package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.dispatch.AssignmentType;

/**
 * What the courier does with a parcel: collect it (a PICKUP task) or take it on (a TRANSFER or DELIVERY task). The
 * task's {@code type} in the response says which of the two the latter is.
 */
public enum Stop {
    PICKUP,
    DELIVERY;

    static Stop of(CourierTask task) {
        return task.type() == AssignmentType.PICKUP ? PICKUP : DELIVERY;
    }
}
