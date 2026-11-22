package com.swiftdrop.dispatch.internal.dto;

import java.util.UUID;

/** The courier doing a task, as the dispatcher sees them. {@code fullName} is null for an unknown id. */
public record CourierRef(UUID id, String fullName, String phone) {
}
