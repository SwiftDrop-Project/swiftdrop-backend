package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.shared.reference.District;

/** A town and its district: all the public tracking page shows of an address. */
public record TrackingPlace(String city, District district) {
}
