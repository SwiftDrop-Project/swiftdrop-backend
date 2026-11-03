package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.notifications.AccountEmail;
import com.swiftdrop.notifications.NotificationsApi;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Implements {@link NotificationsApi}. Publishing an event (instead of sending here) ties the email to the caller's
 * transaction: {@code AccountEmailSender} only runs after a commit, so a rolled-back sign-up never sends a code.
 */
@Service
public class AccountEmailService implements NotificationsApi {

    private final ApplicationEventPublisher events;

    public AccountEmailService(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void sendAccountEmail(AccountEmail email) {
        events.publishEvent(new AccountEmailRequested(email));
    }
}
