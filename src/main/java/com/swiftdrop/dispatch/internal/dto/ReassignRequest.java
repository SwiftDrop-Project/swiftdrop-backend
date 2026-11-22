package com.swiftdrop.dispatch.internal.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of POST /dispatch/assignments/{id}/reassign. */
public record ReassignRequest(@NotNull(message = "Select a courier.") UUID courierId) {
}
