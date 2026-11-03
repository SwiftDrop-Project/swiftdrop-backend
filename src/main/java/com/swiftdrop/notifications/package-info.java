/**
 * Notifications: emails (and later SMS) to users: account emails and parcel status updates.
 *
 * <p>Account emails (built): other modules call {@link com.swiftdrop.notifications.NotificationsApi#sendAccountEmail}
 * with an {@link com.swiftdrop.notifications.AccountEmail} (verification code, password reset code, password changed).
 * The email is rendered from {@code resources/templates/email/}, sent through SMTP ({@code JavaMailSender}: Mailpit
 * locally, Gmail with an App Password on the shared setup) asynchronously after the caller's transaction commits,
 * and recorded in {@code notifications.email_log} (type, masked address, SENT/FAILED, error class). Codes and full
 * addresses are never stored or logged.
 *
 * <p>Owner: TBD. Database schema: notifications (table {@code email_log}). Events published: none.
 * Events consumed: {@code ParcelStatusChanged} from RabbitMQ queue {@code notifications.q} (not built yet).
 *
 * <p>Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Notifications")
package com.swiftdrop.notifications;

import org.springframework.modulith.ApplicationModule;
