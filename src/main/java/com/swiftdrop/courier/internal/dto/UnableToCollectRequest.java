package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.dispatch.TaskProblem;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /courier/deliveries/{parcelId}/unable-to-collect}: why the courier couldn't take the parcel.
 * Recorded as NOT_COLLECTED for a pickup and NOT_LOADED for a delivery. A note of 3 characters or more is required
 * when {@code problem} is OTHER (checked by the service).
 */
public record UnableToCollectRequest(
        @NotNull(message = "Select why you couldn't take the parcel.")
        TaskProblem problem,

        @Size(max = 500, message = "Keep the note under 500 characters.")
        String note) {
}
