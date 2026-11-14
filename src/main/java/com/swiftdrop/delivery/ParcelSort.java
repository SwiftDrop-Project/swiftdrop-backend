package com.swiftdrop.delivery;

/** The order of {@link DeliveryApi#search}. Ties are broken by booking time, then id, so paging is stable. */
public enum ParcelSort {
    /** Newest booking first. */
    NEWEST,
    /** Oldest booking first. */
    OLDEST,
    /** Highest delivery fee first. */
    FEE_HIGH,
    /** Lowest delivery fee first. */
    FEE_LOW
}
