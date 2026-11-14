package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.TaskProblem;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /courier/assignments/{id}/items/{parcelId}/outcome}. Only the simple rules are annotations;
 * the rules that depend on the outcome (problem, failure reason, recipient name, note for OTHER) are checked by the
 * service.
 */
public record ItemOutcomeRequest(
        @NotNull(message = "Select what happened.")
        ItemOutcome outcome,

        TaskProblem problem,

        FailureReason failureReason,

        @Size(max = 500, message = "Keep the note under 500 characters.")
        String note,

        @Size(max = 100, message = "Enter the name of the person who received the parcel.")
        String recipientName) {
}
