package com.swiftdrop.notifications.internal.domain;

public enum EmailStatus {
    /** The SMTP server accepted the message. */
    SENT,
    /** Rendering or sending failed; {@code error_class} says why. */
    FAILED
}
