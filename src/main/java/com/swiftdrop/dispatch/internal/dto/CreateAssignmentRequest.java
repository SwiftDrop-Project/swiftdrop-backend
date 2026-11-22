package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.shared.reference.District;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body of POST /dispatch/assignments. Branches per type: PICKUP needs only {@code destinationBranch}; TRANSFER needs
 * both, and they differ; DELIVERY needs only {@code originBranch}. {@code scheduledFor} defaults to today.
 */
public record CreateAssignmentRequest(
        @NotNull(message = "Select a courier.") UUID courierId,
        @NotNull(message = "Choose the type of task.") AssignmentType type,
        District originBranch,
        District destinationBranch,
        LocalDate scheduledFor,
        @Size(max = 500, message = "Keep the note under 500 characters.") String note,
        @NotNull(message = "Select at least one parcel.")
        @Size(min = 1, max = 50, message = "Select between 1 and 50 parcels.")
        List<@NotNull(message = "Select at least one parcel.") UUID> parcelIds) {
}
