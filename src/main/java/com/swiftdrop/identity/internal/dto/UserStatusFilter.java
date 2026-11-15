package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.UserStatus;

import java.util.EnumSet;
import java.util.Set;

/** The {@code status} filter of GET /admin/users: account statuses grouped the way the admin page shows them. */
public enum UserStatusFilter {
    ACTIVE(EnumSet.of(UserStatus.ACTIVE)),
    BLOCKED(EnumSet.of(UserStatus.DISABLED)),
    /** Email not confirmed, merchant waiting for approval, or Google sign-up not finished. */
    PENDING(EnumSet.of(UserStatus.PENDING_EMAIL_VERIFICATION, UserStatus.PENDING_APPROVAL,
            UserStatus.PROFILE_INCOMPLETE)),
    REJECTED(EnumSet.of(UserStatus.REJECTED)),
    /** Soft-deleted accounts. Every other filter (and no filter) leaves them out. */
    REMOVED(EnumSet.of(UserStatus.REMOVED));

    private final Set<UserStatus> statuses;

    UserStatusFilter(Set<UserStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<UserStatus> statuses() {
        return EnumSet.copyOf(statuses);
    }
}
