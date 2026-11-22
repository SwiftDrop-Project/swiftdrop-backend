package com.swiftdrop.customer.internal.dto;

import com.swiftdrop.shared.validation.SriLankanMobile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePersonalDetailsRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name cannot exceed 100 characters")
        String fullName,

        @NotBlank(message = "Mobile number is required")
        @SriLankanMobile
        String phone
) {
}
