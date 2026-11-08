package com.swiftdrop.courier.internal.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /courier/assignments/{id}/handover}: the parcels the courier hands over at the branch, all or
 * nothing. Uniqueness and the {@code receivedBy} minimum length are checked by the service.
 */
public record HandoverRequest(
        @NotEmpty(message = "Select at least one parcel.")
        @Size(max = 100, message = "Hand over at most 100 parcels at a time.")
        List<@NotNull(message = "Select at least one parcel.") UUID> parcelIds,

        @Size(max = 100, message = "Enter the name of the person at the branch who received the parcels.")
        String receivedBy,

        @Size(max = 500, message = "Keep the note under 500 characters.")
        String note) {
}
