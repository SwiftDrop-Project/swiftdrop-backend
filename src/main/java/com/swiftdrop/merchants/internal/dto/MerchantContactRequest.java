package com.swiftdrop.merchants.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import com.swiftdrop.shared.validation.SriLankanMobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A pickup or recipient in a merchant order. Same rules as the customer booking's contact; kept here because the
 * merchants module may not reach into delivery's internal DTOs.
 */
public record MerchantContactRequest(
        @Schema(example = "Lanka Crafts")
        @NotBlank(message = "Enter a name.") @Size(max = 100, message = "Use 100 characters or fewer.") String name,
        @Schema(example = "077 123 4567") @SriLankanMobile String phone,
        @Schema(example = "12 High Level Road")
        @NotBlank(message = "Enter the address.") @Size(max = 200, message = "Use 200 characters or fewer.")
        String addressLine,
        @Schema(example = "Nugegoda")
        @NotBlank(message = "Enter the city.") @Size(max = 80, message = "Use 80 characters or fewer.") String city,
        @Schema(example = "COLOMBO") @NotNull(message = "Select a district.") District district) {

    public DeliveryContact toContact() {
        String storedPhone = PhoneNumberNormalizer.normalize(phone).orElseThrow();
        return new DeliveryContact(name.strip(), storedPhone, addressLine.strip(), city.strip(), district);
    }

    @Override
    public String toString() {
        return "MerchantContactRequest[city=" + city + ", district=" + district + "]";
    }
}
