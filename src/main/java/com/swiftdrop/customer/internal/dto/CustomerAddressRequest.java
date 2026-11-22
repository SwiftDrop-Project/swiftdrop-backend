package com.swiftdrop.customer.internal.dto;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.validation.SriLankanMobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /customer/addresses and PUT /customer/addresses/{id}. The same rules as a delivery's pickup and
 * recipient (API.md), so a saved address can always fill a booking. {@code useAsDefault}: true makes it the default
 * pickup address; false on an edit stops it being the default; null leaves the default as it is.
 */
public record CustomerAddressRequest(
        @Schema(example = "Home")
        @NotBlank(message = "Enter a label, like Home or Office.")
        @Size(max = 40, message = "Use 40 characters or fewer.")
        String label,

        @Schema(example = "Nimali Perera")
        @NotBlank(message = "Enter a name.")
        @Size(max = 100, message = "Use 100 characters or fewer.")
        String contactName,

        @Schema(example = "077 123 4567")
        @SriLankanMobile
        String phone,

        @Schema(example = "24 Temple Road")
        @NotBlank(message = "Enter the address.")
        @Size(max = 200, message = "Use 200 characters or fewer.")
        String addressLine,

        @Schema(example = "Nugegoda")
        @NotBlank(message = "Enter the city.")
        @Size(max = 80, message = "Use 80 characters or fewer.")
        String city,

        @Schema(example = "COLOMBO")
        @NotNull(message = "Select a district.")
        District district,

        @Schema(example = "10250")
        @NotBlank(message = "Enter the 5-digit postal code.")
        @Pattern(regexp = "^\\s*\\d{5}\\s*$", message = "Enter the 5-digit postal code.")
        String postalCode,

        @Schema(example = "Opposite the post office")
        @Size(max = 150, message = "Use 150 characters or fewer.")
        String landmark,

        @Schema(example = "true")
        Boolean useAsDefault) {

    /** Contacts are personal data: keep them out of logs. */
    @Override
    public String toString() {
        return "CustomerAddressRequest[label=" + label + ", district=" + district + ", useAsDefault=" + useAsDefault
                + "]";
    }
}
