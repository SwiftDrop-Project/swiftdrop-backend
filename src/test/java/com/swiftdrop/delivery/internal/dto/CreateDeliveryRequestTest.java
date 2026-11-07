package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.shared.reference.District;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CreateDeliveryRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aCompleteRequestIsValid() {
        var request = new CreateDeliveryRequest(contact("077 123 4567"), contact("0719876543"),
                new BigDecimal("2.50"), new BigDecimal("2500.00"), "Call before coming");

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void eachBrokenRuleNamesItsField() {
        var broken = new CreateDeliveryRequest(contact("12345"), null, new BigDecimal("0"), new BigDecimal("-5"),
                "x".repeat(501));

        assertThat(messagesByField(VALIDATOR.validate(broken)))
                .containsEntry("pickup.phone", "Enter a Sri Lankan mobile number, like 077 123 4567.")
                .containsEntry("recipient", "Enter the recipient's details.")
                .containsEntry("weightKg", "Enter a weight above 0 kg.")
                .containsEntry("codAmount", "Enter an amount above 0, or leave it out.")
                .containsEntry("instructions", "Use 500 characters or fewer.");
    }

    @Test
    void aParcelCanWeighUpTo50Kg() {
        var fifty = new CreateDeliveryRequest(contact("0771234567"), contact("0719876543"), new BigDecimal("50.00"),
                null, null);
        var tooHeavy = new CreateDeliveryRequest(contact("0771234567"), contact("0719876543"),
                new BigDecimal("50.01"), null, null);

        assertThat(VALIDATOR.validate(fifty)).isEmpty();
        assertThat(messagesByField(VALIDATOR.validate(tooHeavy)))
                .containsEntry("weightKg", "A parcel can weigh up to 50 kg.");
    }

    @Test
    void theCommandHasTheCustomerAndTheStoredPhoneForm() {
        UUID customerId = UUID.randomUUID();
        var pickup = new ContactRequest("  Nimal Perera ", "077 123 4567", "12 Galle Road", "Colombo 03",
                District.COLOMBO);
        var request = new CreateDeliveryRequest(pickup, contact("0719876543"), new BigDecimal("2.50"), null, "   ");

        CreateDeliveryCommand command = request.toCommand(customerId);

        assertThat(command.customerId()).isEqualTo(customerId);
        assertThat(command.merchantId()).isNull();
        assertThat(command.pickup().name()).isEqualTo("Nimal Perera");
        assertThat(command.pickup().phone()).isEqualTo("+94771234567");
        assertThat(command.recipient().phone()).isEqualTo("+94719876543");
        assertThat(command.weightKg()).isEqualByComparingTo("2.50");
        // blank instructions mean "none"
        assertThat(command.instructions()).isNull();
    }

    @Test
    void printingARequestShowsNoPersonalData() {
        var request = new CreateDeliveryRequest(contact("077 123 4567"), contact("0719876543"),
                new BigDecimal("2.50"), null, "Call Kamala before coming");

        assertThat(request.toString()).doesNotContain("Nimal", "077", "Galle", "Kamala");
        assertThat(request.pickup().toString()).doesNotContain("Nimal", "077", "Galle");
    }

    private static ContactRequest contact(String phone) {
        return new ContactRequest("Nimal Perera", phone, "12 Galle Road", "Colombo 03", District.COLOMBO);
    }

    private static <T> Map<String, String> messagesByField(Set<ConstraintViolation<T>> violations) {
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage, (first, second) -> first));
    }
}
