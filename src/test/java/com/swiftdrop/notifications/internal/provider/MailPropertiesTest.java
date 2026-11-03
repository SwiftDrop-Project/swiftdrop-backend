package com.swiftdrop.notifications.internal.provider;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailPropertiesTest {

    @Test
    void plainAddressGetsTheDefaultName() {
        InternetAddress sender = new MailProperties("no-reply@swiftdrop.lk", null).sender();

        assertThat(sender.getAddress()).isEqualTo("no-reply@swiftdrop.lk");
        assertThat(sender.getPersonal()).isEqualTo("SwiftDrop");
    }

    @Test
    void nameAndAddressFormIsSplitSoMailFromIsAValidAddress() {
        // This form used to be sent whole as the SMTP MAIL FROM address, which Gmail rejects.
        InternetAddress sender = new MailProperties("SwiftDrop <team@gmail.com>", null).sender();

        assertThat(sender.getAddress()).isEqualTo("team@gmail.com");
        assertThat(sender.getPersonal()).isEqualTo("SwiftDrop");
        assertThat(new MailProperties("\"SwiftDrop Lanka\" <team@gmail.com>", "Other").sender().getPersonal())
                .isEqualTo("SwiftDrop Lanka");
    }

    @Test
    void invalidValuesAreReportedAtStartup() {
        for (String bad : new String[]{"SwiftDrop", "team@", "two words@gmail.com", "SwiftDrop <team@gmail.com"}) {
            MailProperties properties = new MailProperties(bad, null);
            assertThat(properties.isFromValid()).as(bad).isFalse();
            assertThatThrownBy(properties::sender).isInstanceOf(IllegalStateException.class);
        }
        assertThat(new MailProperties("", null).isFromValid()).isTrue(); // @NotBlank reports blank values
    }
}
