
package com.swiftdrop.settings.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NotificationSettingsRequest(

    @NotNull
    Boolean parcelStatusEmails,

    @NotNull
    Boolean merchantDecisionEmails,

    @NotBlank
    @Size(max = 100)
    String senderName

) {}
