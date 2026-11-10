package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent;
import com.swiftdrop.pricing.internal.domain.FeeQuote.FeeLine;
import com.swiftdrop.pricing.internal.domain.FeeQuote.QuoteStatus;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements.Dimensions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.BASE;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.COD;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.DISTANCE;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.VEHICLE_SURCHARGE;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.WEIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The fee rules, with the spec's own examples and rate card 1 (the migration's demo values). */
class FeeCalculatorTest {

    private static final VehicleRate BIKE = new VehicleRate(Vehicle.BIKE, 1, 45, 35, 35, kg("10"), rs("0"),
            new BigDecimal("5.0"), rs("30"));
    private static final VehicleRate TUK_TUK = new VehicleRate(Vehicle.TUK_TUK, 2, 80, 60, 60, kg("40"), rs("300"),
            new BigDecimal("5.0"), rs("60"));
    private static final VehicleRate LORRY = new VehicleRate(Vehicle.LORRY, 3, 250, 150, 150, kg("500"), rs("1500"),
            new BigDecimal("10.0"), rs("150"));
    private static final RateCard CARD = new RateCard(1, "LKR", rs("350"), 1, rs("200"), 5000, rs("100"),
            new BigDecimal("1.30"), List.of(BIKE, TUK_TUK, LORRY));

    private final FeeCalculator calculator = new FeeCalculator();

    // ---- The spec's examples ----

    @Test
    void theSpecsBikeExampleCosts960() {
        // 1.8 kg, 40 x 30 x 12 cm, 12 km: volumetric 2.88 kg → billed 3 kg; fits a Bike
        FeeQuote quote = calculator.quote(CARD, parcel("1.8", 40, 30, 12, false), km("12"), false);

        assertThat(quote.status()).isEqualTo(QuoteStatus.COMPLETE);
        assertThat(quote.volumetricWeightKg()).isEqualByComparingTo("2.88");
        assertThat(quote.billableWeightKg()).isEqualTo(3);
        assertThat(quote.vehicle()).isEqualTo(Vehicle.BIKE);
        assertThat(quote.lines()).containsExactly(
                line(BASE, "350.00"), line(WEIGHT, "400.00"), line(VEHICLE_SURCHARGE, "0.00"),
                line(DISTANCE, "210.00"));
        assertThat(quote.total()).isEqualByComparingTo("960.00");
        assertThat(quote.rateCardVersion()).isEqualTo(1);
        assertThat(quote.currency()).isEqualTo("LKR");
    }

    @Test
    void aThreeKiloParcelHasA750Subtotal() {
        FeeQuote quote = calculator.quote(CARD, parcel("3", 20, 20, 20, false), km("0"), false);

        assertThat(quote.amountOf(WEIGHT)).hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("400"));
        assertThat(quote.total()).isEqualByComparingTo("750.00");
    }

    @Test
    void aLongParcelNeedsATukTukLyingDownOrALorryUpright() {
        // 1 kg, 10 x 10 x 70 cm: volumetric 1.4 kg → billed 2 kg either way
        FeeQuote lyingDown = calculator.quote(CARD, parcel("1", 10, 10, 70, false), km("3"), false);
        FeeQuote upright = calculator.quote(CARD, parcel("1", 10, 10, 70, true), km("3"), false);

        assertThat(lyingDown.billableWeightKg()).isEqualTo(2);
        assertThat(lyingDown.vehicle()).isEqualTo(Vehicle.TUK_TUK);
        assertThat(upright.billableWeightKg()).isEqualTo(2);
        assertThat(upright.vehicle()).isEqualTo(Vehicle.LORRY);
        assertThat(upright.amountOf(WEIGHT)).isEqualTo(lyingDown.amountOf(WEIGHT));
    }

    // ---- Weight ----

    @Test
    void theFirstKiloIsInTheBaseFee() {
        FeeQuote quote = calculator.quote(CARD, parcel("1", 10, 10, 10, false), km("2"), false);

        assertThat(quote.amountOf(WEIGHT)).hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("0"));
        assertThat(quote.total()).isEqualByComparingTo("350.00");
    }

    @Test
    void anyPartOfAKiloIsBilledAsAWholeOne() {
        assertThat(calculator.quote(CARD, parcel("2.01", 10, 10, 10, false), km("0"), false).billableWeightKg())
                .isEqualTo(3);
        // 50 x 40 x 25 = 50,000 cm³ / 5000 = exactly 10 kg: no rounding up
        assertThat(calculator.quote(CARD, parcel("1", 50, 40, 25, false), km("0"), false).billableWeightKg())
                .isEqualTo(10);
    }

    // ---- Vehicle ----

    @Test
    void aSmallButHeavyParcelSkipsTheBike() {
        FeeQuote quote = calculator.quote(CARD, parcel("15", 20, 20, 20, false), km("0"), false);

        assertThat(quote.vehicle()).isEqualTo(Vehicle.TUK_TUK);
        assertThat(quote.amountOf(VEHICLE_SURCHARGE))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("300"));
    }

    @Test
    void anUprightParcelMayStillSwapItsLengthAndWidth() {
        // 30 x 44 x 30 upright: 44 is longer than the Bike's width (35) but fits its length (45) once swapped
        assertThat(calculator.quote(CARD, parcel("2", 30, 44, 30, true), km("0"), false).vehicle())
                .isEqualTo(Vehicle.BIKE);
    }

    @Test
    void nothingFitsMeansAManualQuote() {
        FeeQuote tooLong = calculator.quote(CARD, parcel("5", 300, 20, 20, false), km("8"), true);
        FeeQuote tooHeavy = calculator.quote(CARD, parcel("600", 50, 50, 50, false), km("8"), false);

        assertThat(tooLong.status()).isEqualTo(QuoteStatus.MANUAL_QUOTE);
        assertThat(tooLong.total()).isNull();
        assertThat(tooLong.vehicle()).isNull();
        assertThat(tooLong.amountOf(DISTANCE)).isEmpty();
        assertThat(tooHeavy.status()).isEqualTo(QuoteStatus.MANUAL_QUOTE);
    }

    @Test
    void vehiclesAreTriedSmallestFirstWhateverOrderTheyComeIn() {
        RateCard shuffled = new RateCard(1, "LKR", rs("350"), 1, rs("200"), 5000, rs("100"), new BigDecimal("1.30"),
                List.of(LORRY, BIKE, TUK_TUK));

        assertThat(shuffled.vehicles()).extracting(VehicleRate::vehicle)
                .containsExactly(Vehicle.BIKE, Vehicle.TUK_TUK, Vehicle.LORRY);
        assertThat(calculator.quote(shuffled, parcel("1", 10, 10, 10, false), km("0"), false).vehicle())
                .isEqualTo(Vehicle.BIKE);
    }

    // ---- Distance and services ----

    @Test
    void theIncludedKilometresCostNothing() {
        assertThat(calculator.quote(CARD, parcel("1", 10, 10, 10, false), km("5.0"), false).amountOf(DISTANCE))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("0"));
        // 5.5 km on a Bike: 0.5 km x 30 = 15.00
        assertThat(calculator.quote(CARD, parcel("1", 10, 10, 10, false), km("5.5"), false).amountOf(DISTANCE))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("15.00"));
    }

    @Test
    void cashOnDeliveryAddsTheHandlingFee() {
        FeeQuote quote = calculator.quote(CARD, parcel("1", 10, 10, 10, false), km("0"), true);

        assertThat(quote.lines()).last().isEqualTo(line(COD, "100.00"));
        assertThat(quote.total()).isEqualByComparingTo("450.00");
    }

    // ---- Without dimensions ----

    @Test
    void withoutDimensionsTheQuoteIsAProvisionalWeightBasedSubtotal() {
        FeeQuote quote = calculator.quote(CARD, new ParcelMeasurements(kg("2.4"), null, false), km("40"), true);

        assertThat(quote.status()).isEqualTo(QuoteStatus.PROVISIONAL);
        assertThat(quote.billableWeightKg()).isEqualTo(3);
        assertThat(quote.volumetricWeightKg()).isNull();
        assertThat(quote.vehicle()).isNull();
        assertThat(quote.distanceKm()).isNull();
        assertThat(quote.lines()).containsExactly(line(BASE, "350.00"), line(WEIGHT, "400.00"), line(COD, "100.00"));
        assertThat(quote.total()).isEqualByComparingTo("850.00");
    }

    // ---- Bad measurements ----

    @Test
    void aWeightOrASideOfZeroIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ParcelMeasurements(kg("0"), null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new Dimensions(10, 0, 10));
    }

    private static ParcelMeasurements parcel(String kg, int lengthCm, int widthCm, int heightCm, boolean upright) {
        return new ParcelMeasurements(kg(kg), new Dimensions(lengthCm, widthCm, heightCm), upright);
    }

    private static FeeLine line(FeeComponent component, String amount) {
        return new FeeLine(component, new BigDecimal(amount));
    }

    private static BigDecimal kg(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal km(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal rs(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
