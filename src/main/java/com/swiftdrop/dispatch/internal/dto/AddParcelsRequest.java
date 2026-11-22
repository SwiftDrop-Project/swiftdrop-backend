package com.swiftdrop.dispatch.internal.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body of POST /dispatch/assignments/{id}/parcels. */
public record AddParcelsRequest(
        @NotNull(message = "Select at least one parcel.")
        @Size(min = 1, max = 50, message = "Select between 1 and 50 parcels.")
        List<@NotNull(message = "Select at least one parcel.") UUID> parcelIds) {
}
