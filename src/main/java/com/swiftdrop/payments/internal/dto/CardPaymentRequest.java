package com.swiftdrop.payments.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /payments/{id}/card: a mock card. Only test card numbers are accepted (API.md "Payments"). None of
 * these values is stored or logged.
 */
public record CardPaymentRequest(
        @Schema(example = "4242 4242 4242 4242")
        @NotBlank(message = "Enter the card number.")
        @Pattern(regexp = "^[0-9 ]{12,23}$", message = "Enter the card number.")
        String cardNumber,

        @Schema(example = "12/28")
        @NotBlank(message = "Enter the expiry date as MM/YY.")
        @Pattern(regexp = "^(0[1-9]|1[0-2])/\\d{2}$", message = "Enter the expiry date as MM/YY.")
        String expiry,

        @Schema(example = "123")
        @NotBlank(message = "Enter the 3 or 4 digit security code.")
        @Pattern(regexp = "^\\d{3,4}$", message = "Enter the 3 or 4 digit security code.")
        String cvc,

        @Schema(example = "Nimali Perera")
        @NotBlank(message = "Enter the name on the card.")
        @Size(max = 100, message = "Use 100 characters or fewer.")
        String nameOnCard) {

    /** Card details never go into logs. */
    @Override
    public String toString() {
        return "CardPaymentRequest[hidden]";
    }
}
