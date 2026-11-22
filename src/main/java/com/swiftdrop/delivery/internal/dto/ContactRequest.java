package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import com.swiftdrop.shared.validation.SriLankanMobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A pickup or recipient in a booking. The phone may be in any accepted form; it is stored as +947XXXXXXXX. */
public record ContactRequest(
        @Schema(example = "Nimal Perera")
        @NotBlank(message = "Enter a name.") @Size(max = 100, message = "Use 100 characters or fewer.") String name,
        @Schema(example = "077 123 4567") @SriLankanMobile String phone,
        @Schema(example = "12 Galle Road")
        @NotBlank(message = "Enter the address.") @Size(max = 200, message = "Use 200 characters or fewer.")
        String addressLine,
        @Schema(example = "Colombo 03")
        @NotBlank(message = "Enter the city.") @Size(max = 80, message = "Use 80 characters or fewer.") String city,
        @Schema(example = "COLOMBO") @NotNull(message = "Select a district.") District district) {

    /** The contract type, with the phone in its stored form. Call it only on a validated request. */
    public DeliveryContact toContact() {
        String storedPhone = PhoneNumberNormalizer.normalize(phone).orElseThrow();
        return new DeliveryContact(name.strip(), storedPhone, addressLine.strip(), city.strip(), district);
    }

    /** Names, phone numbers and addresses must never reach the logs. */
    @Override
    public String toString() {
        return "ContactRequest[city=" + city + ", district=" + district + "]";
    }
}
