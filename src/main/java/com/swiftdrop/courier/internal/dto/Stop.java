package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;

/** What the courier does next with a parcel: collect it (still CREATED) or deliver it (any later status). */
public enum Stop {
    PICKUP,
    DELIVERY;

    static Stop of(ParcelStatus status) {
        return status == ParcelStatus.CREATED ? PICKUP : DELIVERY;
    }
}
