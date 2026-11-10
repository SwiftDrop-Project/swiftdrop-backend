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
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.DELIVERY;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.LINEHAUL;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.PICKUP;
import static com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent.WEIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The fee rules with rate card 2 (the migration's values): base 350 incl. 1 kg, 200 per extra kg, leg fees Bike 150,
 * Tuk-tuk 300, Lorry 1500 for each local leg, linehaul 2.00 per km between the branches, COD 100.
 */
class FeeCalculatorTest {

    private static final VehicleRate BIKE = new VehicleRate(Vehicle.BIKE, 1, 45, 35, 35, kg("10"), rs("150"));
    private static final VehicleRate TUK_TUK = new VehicleRate(Vehicle.TUK_TUK, 2, 80, 60, 60, kg("40"), rs("300"));
    private static final VehicleRate LORRY = new VehicleRate(Vehicle.LORRY, 3, 250, 150, 150, kg("500"), rs("1500"));
    private static final RateCard CARD = new RateCard(2, "LKR", rs("350"), 1, rs("200"), 5000, rs("100"), rs("2"),
            new BigDecimal("1.30"), List.of(BIKE, TUK_TUK, LORRY));

    /** Colombo to Kandy branch: about 94.3 km in a straight line x 1.3. */
    private static final BigDecimal COLOMBO_TO_KANDY = km("122.6");
    private static final BigDecimal SAME_DISTRICT = km("0.0");

    private final FeeCalculator calculator = new FeeCalculator();

    // ---- The route: two local legs by vehicle, linehaul between the branches ----

    @Test
    void colomboToKandyByBikeIsBaseWeightTwoLegsAndTheLinehaul() {
        // 1.8 kg, 40 x 30 x 12 cm: volumetric 2.88 kg → billed 3 kg; fits a Bike
        FeeQuote quote = calculator.quote(CARD, parcel("1.8", 40, 30, 12, false), COLOMBO_TO_KANDY, false);

        assertThat(quote.status()).isEqualTo(QuoteStatus.COMPLETE);
        assertThat(quote.volumetricWeightKg()).isEqualByComparingTo("2.88");
        assertThat(quote.billableWeightKg()).isEqualTo(3);
        assertThat(quote.vehicle()).isEqualTo(Vehicle.BIKE);
        assertThat(quote.distanceKm()).isEqualByComparingTo("122.6");
        assertThat(quote.lines()).containsExactly(
                line(BASE, "350.00"), line(WEIGHT, "400.00"), line(PICKUP, "150.00"), line(DELIVERY, "150.00"),
                line(LINEHAUL, "245.20"));
        assertThat(quote.total()).isEqualByComparingTo("1295.20");
        assertThat(quote.rateCardVersion()).isEqualTo(2);
        assertThat(quote.currency()).isEqualTo("LKR");
    }

    @Test
    void insideOneDistrictThereIsNoLinehaul() {
        FeeQuote quote = calculator.quote(CARD, parcel("1.8", 40, 30, 12, false), SAME_DISTRICT, false);

        assertThat(quote.amountOf(LINEHAUL)).hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("0"));
        assertThat(quote.total()).isEqualByComparingTo("1050.00");
    }

    @Test
    void theVehicleChangesTheLegsButNotTheLinehaul() {
        // 1 kg, 10 x 10 x 70 cm: volumetric 1.4 kg → billed 2 kg; a Tuk-tuk lying down, a Lorry upright
        FeeQuote lyingDown = calculator.quote(CARD, parcel("1", 10, 10, 70, false), COLOMBO_TO_KANDY, false);
        FeeQuote upright = calculator.quote(CARD, parcel("1", 10, 10, 70, true), COLOMBO_TO_KANDY, false);

        assertThat(lyingDown.vehicle()).isEqualTo(Vehicle.TUK_TUK);
        assertThat(lyingDown.amountOf(PICKUP))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("300"));
        assertThat(lyingDown.total()).isEqualByComparingTo("1395.20");
        assertThat(upright.vehicle()).isEqualTo(Vehicle.LORRY);
        assertThat(upright.total()).isEqualByComparingTo("3795.20");
        assertThat(upright.amountOf(LINEHAUL)).isEqualTo(lyingDown.amountOf(LINEHAUL));
        assertThat(upright.amountOf(WEIGHT)).isEqualTo(lyingDown.amountOf(WEIGHT));
    }

    @Test
    void theLinehaulIsTheBranchDistanceTimesTheRate() {
        assertThat(calculator.quote(CARD, parcel("1", 10, 10, 10, false), km("12.0"), false).amountOf(LINEHAUL))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("24.00"));
        assertThat(calculator.quote(CARD, parcel("1", 10, 10, 10, false), km("396.1"), false).amountOf(LINEHAUL))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("792.20"));
    }

    // ---- Weight ----

    @Test
    void theFirstKiloIsInTheBaseFee() {
        FeeQuote quote = calculator.quote(CARD, parcel("1", 10, 10, 10, false), SAME_DISTRICT, false);

        assertThat(quote.amountOf(WEIGHT)).hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("0"));
        assertThat(quote.total()).isEqualByComparingTo("650.00");
    }

    @Test
    void anyPartOfAKiloIsBilledAsAWholeOne() {
        assertThat(calculator.quote(CARD, parcel("2.01", 10, 10, 10, false), SAME_DISTRICT, false).billableWeightKg())
                .isEqualTo(3);
        // 50 x 40 x 25 = 50,000 cm³ / 5000 = exactly 10 kg: no rounding up
        assertThat(calculator.quote(CARD, parcel("1", 50, 40, 25, false), SAME_DISTRICT, false).billableWeightKg())
                .isEqualTo(10);
    }

    // ---- Vehicle ----

    @Test
    void aSmallButHeavyParcelSkipsTheBike() {
        FeeQuote quote = calculator.quote(CARD, parcel("15", 20, 20, 20, false), SAME_DISTRICT, false);

        assertThat(quote.vehicle()).isEqualTo(Vehicle.TUK_TUK);
        assertThat(quote.amountOf(DELIVERY))
                .hasValueSatisfying(amount -> assertThat(amount).isEqualByComparingTo("300"));
    }

    @Test
    void anUprightParcelMayStillSwapItsLengthAndWidth() {
        // 30 x 44 x 30 upright: 44 is longer than the Bike's width (35) but fits its length (45) once swapped
        assertThat(calculator.quote(CARD, parcel("2", 30, 44, 30, true), SAME_DISTRICT, false).vehicle())
                .isEqualTo(Vehicle.BIKE);
    }

    @Test
    void nothingFitsMeansAManualQuote() {
        FeeQuote tooLong = calculator.quote(CARD, parcel("5", 300, 20, 20, false), COLOMBO_TO_KANDY, true);
        FeeQuote tooHeavy = calculator.quote(CARD, parcel("600", 50, 50, 50, false), COLOMBO_TO_KANDY, false);

        assertThat(tooLong.status()).isEqualTo(QuoteStatus.MANUAL_QUOTE);
        assertThat(tooLong.total()).isNull();
        assertThat(tooLong.vehicle()).isNull();
        assertThat(tooLong.amountOf(PICKUP)).isEmpty();
        assertThat(tooLong.lines()).extracting(FeeLine::component).containsExactly(BASE, WEIGHT, LINEHAUL, COD);
        assertThat(tooHeavy.status()).isEqualTo(QuoteStatus.MANUAL_QUOTE);
    }

    @Test
    void vehiclesAreTriedSmallestFirstWhateverOrderTheyComeIn() {
        RateCard shuffled = new RateCard(2, "LKR", rs("350"), 1, rs("200"), 5000, rs("100"), rs("2"),
                new BigDecimal("1.30"), List.of(LORRY, BIKE, TUK_TUK));

        assertThat(shuffled.vehicles()).extracting(VehicleRate::vehicle)
                .containsExactly(Vehicle.BIKE, Vehicle.TUK_TUK, Vehicle.LORRY);
        assertThat(calculator.quote(shuffled, parcel("1", 10, 10, 10, false), SAME_DISTRICT, false).vehicle())
                .isEqualTo(Vehicle.BIKE);
    }

    // ---- Services ----

    @Test
    void cashOnDeliveryAddsTheHandlingFeeLast() {
        FeeQuote quote = calculator.quote(CARD, parcel("1", 10, 10, 10, false), SAME_DISTRICT, true);

        assertThat(quote.lines()).last().isEqualTo(line(COD, "100.00"));
        assertThat(quote.total()).isEqualByComparingTo("750.00");
    }

    // ---- Without dimensions ----

    @Test
    void withoutDimensionsTheQuoteIsProvisionalWithoutTheLocalLegs() {
        FeeQuote quote = calculator.quote(CARD, new ParcelMeasurements(kg("2.4"), null, false), COLOMBO_TO_KANDY,
                true);

        assertThat(quote.status()).isEqualTo(QuoteStatus.PROVISIONAL);
        assertThat(quote.billableWeightKg()).isEqualTo(3);
        assertThat(quote.volumetricWeightKg()).isNull();
        assertThat(quote.vehicle()).isNull();
        assertThat(quote.distanceKm()).isEqualByComparingTo("122.6");
        assertThat(quote.lines()).containsExactly(
                line(BASE, "350.00"), line(WEIGHT, "400.00"), line(LINEHAUL, "245.20"), line(COD, "100.00"));
        assertThat(quote.total()).isEqualByComparingTo("1095.20");
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
