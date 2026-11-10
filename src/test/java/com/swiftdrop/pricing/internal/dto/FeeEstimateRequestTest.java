package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeeEstimateRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aCompleteRequestIsValidAndBecomesAQuoteRequest() {
        var request = new FeeEstimateRequest(District.COLOMBO, District.KANDY, new BigDecimal("1.80"), 40, 30, 12,
                true, true);

        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.toQuoteRequest()).isEqualTo(new QuoteRequest(District.COLOMBO, District.KANDY,
                new BigDecimal("1.80"), 40, 30, 12, true, true));
    }

    @Test
    void theMeasurementsMayBeLeftOut() {
        var request = new FeeEstimateRequest(District.COLOMBO, District.KANDY, new BigDecimal("2"), null, null, null,
                false, false);

        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.toQuoteRequest().hasDimensions()).isFalse();
    }

    @Test
    void eachBrokenRuleNamesItsField() {
        var broken = new FeeEstimateRequest(null, null, new BigDecimal("51"), 0, 301, 20, false, false);

        assertThat(messagesByField(VALIDATOR.validate(broken)))
                .containsEntry("pickupDistrict", "Select the pickup district.")
                .containsEntry("dropOffDistrict", "Select the drop-off district.")
                .containsEntry("weightKg", "A parcel can weigh up to 50 kg.")
                .containsEntry("lengthCm", FeeEstimateRequest.MEASUREMENT)
                .containsEntry("widthCm", FeeEstimateRequest.MEASUREMENT)
                .doesNotContainKey("heightCm");
    }

    @Test
    void someMeasurementsWithoutTheOthersNameTheFirstEmptyOne() {
        var twoOfThree = new FeeEstimateRequest(District.COLOMBO, District.KANDY, new BigDecimal("2"), 40, null, 12,
                false, false);

        assertThatThrownBy(twoOfThree::toQuoteRequest)
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.fieldErrors())
                        .containsExactlyEntriesOf(Map.of("widthCm", FeeEstimateRequest.ALL_OR_NONE)));
    }

    private static Map<String, String> messagesByField(Set<ConstraintViolation<FeeEstimateRequest>> violations) {
        return violations.stream().collect(Collectors.toMap(violation -> violation.getPropertyPath().toString(),
                ConstraintViolation::getMessage, (first, second) -> first));
    }
}
