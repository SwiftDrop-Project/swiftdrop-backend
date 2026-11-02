package com.swiftdrop.delivery;

/**
 * The life cycle of a parcel.
 *
 * <p>Contract owned by delivery - extend in Phase 0.
 */
public enum ParcelStatus {
    CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED
}
