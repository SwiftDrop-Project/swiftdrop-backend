package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.notifications.AccountEmail;

/** Internal event: an account email to send once the publishing transaction has committed. */
public record AccountEmailRequested(AccountEmail email) {

    @Override
    public String toString() {
        return "AccountEmailRequested[type=" + email.type() + "]";
    }
}
