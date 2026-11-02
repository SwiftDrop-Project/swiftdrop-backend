package com.swiftdrop.shared.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumberNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0771234567",
            "771234567",
            "+94771234567",
            "077 123 4567",
            "77 123 4567",
            "+94 77 123 4567",
            " 0771234567 ",
            "077\t123\t4567"})
    void acceptedFormatsAreNormalised(String input) {
        assertThat(PhoneNumberNormalizer.normalize(input)).contains("+94771234567");
        assertThat(PhoneNumberNormalizer.isValid(input)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "0112345678",      // Colombo landline
            "+94112345678",    // landline with country code
            "077123456",       // too short
            "07712345678",     // too long
            "+9477123456",     // too short with country code
            "94771234567",     // country code without +
            "+44771234567",    // other country
            "077-123-4567",    // dashes are not accepted
            "07712a4567",      // letters
            "0077123456"})
    void invalidNumbersAreRejected(String input) {
        assertThat(PhoneNumberNormalizer.normalize(input)).isEmpty();
        assertThat(PhoneNumberNormalizer.isValid(input)).isFalse();
    }

    @Test
    void everyMobilePrefixIsKept() {
        assertThat(PhoneNumberNormalizer.normalize("0701234567")).contains("+94701234567");
        assertThat(PhoneNumberNormalizer.normalize("0781234567")).contains("+94781234567");
    }
}
