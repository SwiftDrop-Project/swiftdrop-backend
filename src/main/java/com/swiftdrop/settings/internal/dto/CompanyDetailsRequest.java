
package com.swiftdrop.settings.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CompanyDetailsRequest(

    @NotBlank(message = "Company name is required")
    String companyName,

    @NotBlank(message = "Support email is required")
    @Email(message = "Enter a valid email address")
    String supportEmail,

    @NotBlank(message = "Support phone is required")
    @Pattern(
        regexp = "^(?:\\+94|0)?7\\d{8}$",
        message = "Enter a valid Sri Lankan mobile number"
    )
    String supportPhone,

    @NotBlank(message = "Head office address is required")
    String headOfficeAddress,

    String registrationNumber

) {}
