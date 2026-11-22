package com.swiftdrop.pricing.internal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoDistanceTest {

    // district centres from the migration
    private static final double COLOMBO_LAT = 6.92710;
    private static final double COLOMBO_LON = 79.86120;
    private static final double KANDY_LAT = 7.29060;
    private static final double KANDY_LON = 80.63370;
    private static final double JAFFNA_LAT = 9.66150;
    private static final double JAFFNA_LON = 80.02550;

    @Test
    void colomboToKandyIsAbout94KmInAStraightLine() {
        assertThat(GeoDistance.haversineKm(COLOMBO_LAT, COLOMBO_LON, KANDY_LAT, KANDY_LON))
                .isCloseTo(94.3, within(0.5));
    }

    @Test
    void colomboToJaffnaIsAbout305KmInAStraightLine() {
        assertThat(GeoDistance.haversineKm(COLOMBO_LAT, COLOMBO_LON, JAFFNA_LAT, JAFFNA_LON))
                .isCloseTo(304.6, within(1.0));
    }

    @Test
    void itIsTheSameBothWaysAndZeroForOnePoint() {
        assertThat(GeoDistance.haversineKm(KANDY_LAT, KANDY_LON, COLOMBO_LAT, COLOMBO_LON))
                .isCloseTo(GeoDistance.haversineKm(COLOMBO_LAT, COLOMBO_LON, KANDY_LAT, KANDY_LON), within(1e-9));
        assertThat(GeoDistance.haversineKm(KANDY_LAT, KANDY_LON, KANDY_LAT, KANDY_LON)).isZero();
    }
}
