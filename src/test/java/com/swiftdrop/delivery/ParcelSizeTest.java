package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ParcelSizeTest {

    @Test
    void everyMeasurementMustBeMoreThanZero() {
        assertThat(new ParcelSize(40, 30, 12, false).keepUpright()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new ParcelSize(0, 30, 12, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParcelSize(40, -1, 12, true));
    }

    @Test
    void aCommandFromBeforeSizesHasNone() {
        var command = new CreateDeliveryCommand(UUID.randomUUID(), null, null,
                new DeliveryContact("Nimal", "+94771234567", "12 Galle Road", "Colombo", District.COLOMBO),
                new DeliveryContact("Kamala", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY),
                BigDecimal.ONE, null, null);

        assertThat(command.size()).isNull();
    }
}
