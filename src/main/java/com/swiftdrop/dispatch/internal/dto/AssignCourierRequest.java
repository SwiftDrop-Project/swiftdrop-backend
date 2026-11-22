package com.swiftdrop.dispatch.internal.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of POST /dispatch/parcels/{parcelId}/assign. */
public record AssignCourierRequest(
        @NotNull(message = "Select a courier.") UUID courierId) {
}

