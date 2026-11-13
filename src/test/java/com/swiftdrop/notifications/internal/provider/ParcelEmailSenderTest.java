
package com.swiftdrop.notifications.internal.provider;

import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParcelEmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private IdentityApi identityApi;

    private ParcelEmailSender sender;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        MailProperties properties =
                new MailProperties("no-reply@swiftdrop.lk", "SwiftDrop");

        sender = new ParcelEmailSender(
                mailSender, properties, identityApi);
    }

    @Test
    void sendsEmailForExistingCustomer() {
        UUID customerId = UUID.randomUUID();

        UserSummary customer = new UserSummary(
                customerId,
                "Test Customer",
                "customer@example.com",
                "+94771234567",
                null,
                null,
                null
        );

        when(identityApi.findById(customerId))
                .thenReturn(Optional.of(customer));

        MimeMessage message = new MimeMessage(
                Session.getInstance(new Properties()));

        when(mailSender.createMimeMessage())
                .thenReturn(message);

        sender.sendStatusEmail(
                customerId, "SWD12345", "DELIVERED");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void rejectsUnknownCustomer() {
        UUID customerId = UUID.randomUUID();

        when(identityApi.findById(customerId))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> sender.sendStatusEmail(
                        customerId, "SWD12345", "DELIVERED"));

        verify(mailSender, never())
                .send(any(MimeMessage.class));
    }

    @Test
    void rejectsMissingCustomerId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> sender.sendStatusEmail(
                        null, "SWD12345", "DELIVERED"));

        verifyNoInteractions(mailSender);
    }
}
