package com.swiftdrop.pricing;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class QuoteRequestTest {

    @Test
    void theMeasurementsComeTogetherOrNotAtAll() {
        assertThat(new QuoteRequest(District.COLOMBO, District.KANDY, BigDecimal.ONE, 10, 10, 10, false, false)
                .hasDimensions()).isTrue();
        assertThat(new QuoteRequest(District.COLOMBO, District.KANDY, BigDecimal.ONE, null, null, null, false, false)
                .hasDimensions()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new QuoteRequest(District.COLOMBO, District.KANDY,
                BigDecimal.ONE, 10, null, 10, false, false));
    }

    @Test
    void theRouteAndTheWeightAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new QuoteRequest(null, District.KANDY, BigDecimal.ONE,
                null, null, null, false, false));
        assertThatNullPointerException().isThrownBy(() -> new QuoteRequest(District.COLOMBO, District.KANDY, null,
                null, null, null, false, false));
    }
}
