package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.shared.reference.District;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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
        var request = request(contact("077 123 4567"), contact("0719876543"), new BigDecimal("2.50"),
                new BigDecimal("2500.00"), "Call before coming", 40, 30, 12, true);

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void eachBrokenRuleNamesItsField() {
        var broken = request(contact("12345"), null, new BigDecimal("0"), new BigDecimal("-5"), "x".repeat(501),
                0, null, 301, null);

        assertThat(messagesByField(VALIDATOR.validate(broken)))
                .containsEntry("pickup.phone", "Enter a Sri Lankan mobile number, like 077 123 4567.")
                .containsEntry("recipient", "Enter the recipient's details.")
                .containsEntry("weightKg", "Enter a weight above 0 kg.")
                .containsEntry("codAmount", "Enter an amount above 0, or leave it out.")
                .containsEntry("instructions", "Use 500 characters or fewer.")
                .containsEntry("lengthCm", CreateDeliveryRequest.MEASUREMENT)
                .containsEntry("widthCm", "Enter the width.")
                .containsEntry("heightCm", CreateDeliveryRequest.MEASUREMENT);
    }

    @Test
    void theMeasurementsAreRequired() {
        var noSize = request(contact("0771234567"), contact("0719876543"), new BigDecimal("2"), null, null,
                null, null, null, null);

        assertThat(messagesByField(VALIDATOR.validate(noSize)))
                .containsEntry("lengthCm", "Enter the length.")
                .containsEntry("widthCm", "Enter the width.")
                .containsEntry("heightCm", "Enter the height.");
    }

    @Test
    void aParcelCanWeighUpTo50Kg() {
        var fifty = request(contact("0771234567"), contact("0719876543"), new BigDecimal("50.00"), null, null,
                40, 30, 12, null);
        var tooHeavy = request(contact("0771234567"), contact("0719876543"), new BigDecimal("50.01"), null, null,
                40, 30, 12, null);

        assertThat(VALIDATOR.validate(fifty)).isEmpty();
        assertThat(messagesByField(VALIDATOR.validate(tooHeavy)))
                .containsEntry("weightKg", "A parcel can weigh up to 50 kg.");
    }

    @Test
    void theCommandHasTheCustomerTheStoredPhoneFormAndTheSize() {
        UUID customerId = UUID.randomUUID();
        var pickup = new ContactRequest("  Nimal Perera ", "077 123 4567", "12 Galle Road", "Colombo 03",
                District.COLOMBO);
        var request = request(pickup, contact("0719876543"), new BigDecimal("2.50"), null, "   ", 40, 30, 12, true);

        CreateDeliveryCommand command = request.toCommand(customerId);

        assertThat(command.customerId()).isEqualTo(customerId);
        assertThat(command.merchantId()).isNull();
        assertThat(command.pickup().name()).isEqualTo("Nimal Perera");
        assertThat(command.pickup().phone()).isEqualTo("+94771234567");
        assertThat(command.recipient().phone()).isEqualTo("+94719876543");
        assertThat(command.weightKg()).isEqualByComparingTo("2.50");
        assertThat(command.size()).isEqualTo(new ParcelSize(40, 30, 12, true));
        // blank instructions mean "none"
        assertThat(command.instructions()).isNull();
    }

    @Test
    void jsonWithoutKeepUprightMeansItMayLieDown() {
        // Read with Jackson as the endpoint does: a missing optional boolean must not be a 400
        var request = JsonMapper.builder().build().readValue("""
                {"pickup": {"name": "Nimal", "phone": "0771234567", "addressLine": "12 Galle Road",
                            "city": "Colombo", "district": "COLOMBO"},
                 "recipient": {"name": "Kamala", "phone": "0719876543", "addressLine": "45 Peradeniya Road",
                               "city": "Kandy", "district": "KANDY"},
                 "weightKg": 1.8, "lengthCm": 40, "widthCm": 30, "heightCm": 12}""", CreateDeliveryRequest.class);

        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.toCommand(UUID.randomUUID()).size().keepUpright()).isFalse();
    }

    @Test
    void printingARequestShowsNoPersonalData() {
        var request = request(contact("077 123 4567"), contact("0719876543"), new BigDecimal("2.50"), null,
                "Call Kamala before coming", 40, 30, 12, null);

        assertThat(request.toString()).doesNotContain("Nimal", "077", "Galle", "Kamala");
        assertThat(request.pickup().toString()).doesNotContain("Nimal", "077", "Galle");
    }

    private static CreateDeliveryRequest request(ContactRequest pickup, ContactRequest recipient, BigDecimal weightKg,
                                                 BigDecimal codAmount, String instructions, Integer lengthCm,
                                                 Integer widthCm, Integer heightCm, Boolean keepUpright) {
        return new CreateDeliveryRequest(pickup, recipient, weightKg, codAmount, instructions, lengthCm, widthCm,
                heightCm, keepUpright);
    }

    private static ContactRequest contact(String phone) {
        return new ContactRequest("Nimal Perera", phone, "12 Galle Road", "Colombo 03", District.COLOMBO);
    }

    private static <T> Map<String, String> messagesByField(Set<ConstraintViolation<T>> violations) {
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage, (first, second) -> first));
    }
}
