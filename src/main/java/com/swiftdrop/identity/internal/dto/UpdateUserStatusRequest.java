package com.swiftdrop.identity.internal.dto;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /admin/users/{id}/status: {@code {"status": "BLOCKED"}} or {@code {"status": "ACTIVE"}}. */
public record UpdateUserStatusRequest(@NotNull(message = "Choose BLOCKED or ACTIVE.") Change status) {

    /** What the admin wants: block the account, or give a blocked account its access back. */
    public enum Change {
        BLOCKED,
        ACTIVE
    }
}
