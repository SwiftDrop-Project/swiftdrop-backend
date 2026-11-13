package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.shared.reference.District;

/** A city and district (no street address, name or phone). */
public record PlaceResponse(String city, District district) {
}
