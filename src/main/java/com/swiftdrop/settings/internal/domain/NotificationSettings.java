
package com.swiftdrop.settings.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    @Id
    private Long id;

    @Column(name = "parcel_status_emails", nullable = false)
    private Boolean parcelStatusEmails;

    @Column(name = "merchant_decision_emails", nullable = false)
    private Boolean merchantDecisionEmails;

    @Column(name = "sender_name", nullable = false, length = 100)
    private String senderName;

    protected NotificationSettings() {
    }

    public NotificationSettings(
            Long id,
            Boolean parcelStatusEmails,
            Boolean merchantDecisionEmails,
            String senderName
    ) {
        this.id = id;
        this.parcelStatusEmails = parcelStatusEmails;
        this.merchantDecisionEmails = merchantDecisionEmails;
        this.senderName = senderName;
    }

    public Long getId() {
        return id;
    }

    public Boolean getParcelStatusEmails() {
        return parcelStatusEmails;
    }

    public Boolean getMerchantDecisionEmails() {
        return merchantDecisionEmails;
    }

    public String getSenderName() {
        return senderName;
    }
}
