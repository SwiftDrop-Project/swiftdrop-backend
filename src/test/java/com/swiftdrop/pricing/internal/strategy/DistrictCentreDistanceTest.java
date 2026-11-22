package com.swiftdrop.pricing.internal.strategy;

import com.swiftdrop.pricing.internal.domain.DistrictLocation;
import com.swiftdrop.pricing.internal.persistence.DistrictLocationRepository;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DistrictCentreDistanceTest {

    private static final BigDecimal ROAD_FACTOR = new BigDecimal("1.30");

    private final DistrictLocationRepository locations = mock(DistrictLocationRepository.class);
    private final DistrictCentreDistance distance = new DistrictCentreDistance(locations);

    @BeforeEach
    void colomboAndKandyAreKnown() {
        when(locations.findAll()).thenReturn(List.of(
                new DistrictLocation(District.COLOMBO, new BigDecimal("6.92710"), new BigDecimal("79.86120")),
                new DistrictLocation(District.KANDY, new BigDecimal("7.29060"), new BigDecimal("80.63370"))));
    }

    @Test
    void colomboToKandyIsTheStraightLineTimesTheRoadFactor() {
        // about 94.3 km straight x 1.30 = about 122.6 km (the real road is about 115 km)
        BigDecimal km = distance.roadKm(District.COLOMBO, District.KANDY, ROAD_FACTOR);

        assertThat(km.scale()).isEqualTo(1);
        assertThat(km.doubleValue()).isCloseTo(122.6, within(0.6));
        assertThat(distance.roadKm(District.KANDY, District.COLOMBO, ROAD_FACTOR)).isEqualByComparingTo(km);
    }

    @Test
    void aTripInsideOneDistrictIsZeroWithoutReadingTheDatabase() {
        assertThat(distance.roadKm(District.KANDY, District.KANDY, ROAD_FACTOR)).isEqualByComparingTo("0.0");

        verifyNoInteractions(locations);
    }

    @Test
    void theCentresAreReadOnce() {
        distance.roadKm(District.COLOMBO, District.KANDY, ROAD_FACTOR);
        distance.roadKm(District.KANDY, District.COLOMBO, ROAD_FACTOR);

        verify(locations, times(1)).findAll();
    }

    @Test
    void aDistrictWithoutALocationIsAClearError() {
        assertThatIllegalStateException()
                .isThrownBy(() -> distance.roadKm(District.COLOMBO, District.JAFFNA, ROAD_FACTOR))
                .withMessageContaining("JAFFNA");
    }
}
