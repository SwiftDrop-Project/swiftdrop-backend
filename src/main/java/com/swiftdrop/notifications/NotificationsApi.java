package com.swiftdrop.notifications;

/**
 * What other modules may ask the notifications module.
 *
 * <p>Account emails only for now; parcel status notifications are added by the module's owner.
 */
public interface NotificationsApi {

    /**
     * Sends an account email in the background. Fire-and-forget: it never throws because of mail problems.
     *
     * <p>Inside a transaction the email goes out only after that transaction commits (nothing is sent if it rolls
     * back); without a transaction it goes out straight away. The result (SENT/FAILED) is recorded in
     * {@code notifications.email_log} with a masked address.
     */
    void sendAccountEmail(AccountEmail email);
}
