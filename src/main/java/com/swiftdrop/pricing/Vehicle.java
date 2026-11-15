package com.swiftdrop.pricing;

/**
 * The vehicle a parcel needs, from the smallest. The pricing engine picks the first one (by the rate card's check
 * order) that carries the parcel's weight and fits it; each has its own surcharge and per-kilometre rate.
 */
public enum Vehicle {
    BIKE,
    TUK_TUK,
    LORRY
}
