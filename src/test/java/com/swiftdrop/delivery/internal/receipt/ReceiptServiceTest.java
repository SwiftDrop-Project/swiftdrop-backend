package com.swiftdrop.delivery.internal.receipt;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ParcelPrice;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.Line;
import com.swiftdrop.delivery.internal.dto.ReceiptResponse.PaymentStatus;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The receipt's content without Spring: whose receipt it is, the fee lines and the payment status. */
class ReceiptServiceTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final Instant BOOKED_AT = Instant.parse("2026-11-09T05:12:00Z");
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimali Perera", "+94771234567", "24 Temple Road", "Gampaha", District.GAMPAHA);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamal Silva", "+94719876543", "118 Galle Road", "Colombo 03", District.COLOMBO);
    private static final String BREAKDOWN =
            "[{\"component\":\"BASE\",\"amount\":650.00},{\"component\":\"WEIGHT\",\"amount\":330.00}]";

    private final ParcelRepository parcels = mock(ParcelRepository.class);
    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final ReceiptPdfRenderer renderer = mock(ReceiptPdfRenderer.class);
    private final ReceiptService service =
            new ReceiptService(parcels, deliveries, JsonMapper.builder().build(), renderer);

    @Test
    void theCustomersOwnReceiptHasTheBookedFeeLineByLine() {
        Parcel parcel = parcel(CUSTOMER_ID, PaymentMethod.AT_PICKUP, BREAKDOWN);
        when(parcels.findById(parcel.toSnapshot().id())).thenReturn(Optional.of(parcel));

        ReceiptResponse receipt = service.forCustomer(CUSTOMER_ID, parcel.toSnapshot().id());

        assertThat(receipt.trackingNumber()).isEqualTo("SD2611003152");
        assertThat(receipt.status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(receipt.bookedAt()).isEqualTo(BOOKED_AT);
        assertThat(receipt.pickup()).isEqualTo(PICKUP);
        assertThat(receipt.recipient()).isEqualTo(RECIPIENT);
        assertThat(receipt.billableWeightKg()).isEqualTo(2);
        assertThat(receipt.size()).isEqualTo(new ParcelSize(30, 20, 15, false));
        assertThat(receipt.vehicle()).isEqualTo("BIKE");
        assertThat(receipt.lines()).extracting(Line::component, Line::label, Line::amount).containsExactly(
                tuple("BASE", "Base fee", new BigDecimal("650.00")),
                tuple("WEIGHT", "Extra weight (1 kg)", new BigDecimal("330.00")));
        assertThat(receipt.total()).isEqualByComparingTo("980.00");
        assertThat(receipt.currency()).isEqualTo("LKR");
        assertThat(receipt.paymentMethod()).isEqualTo(PaymentMethod.AT_PICKUP);
        assertThat(receipt.paymentStatus()).isEqualTo(PaymentStatus.DUE);
    }

    @Test
    void someoneElsesDeliveryAMerchantOrderAndAnUnknownIdAreAllNotFound() {
        Parcel someoneElses = parcel(UUID.randomUUID(), PaymentMethod.ONLINE, BREAKDOWN);
        Parcel merchantOrder = merchantOrder();
        UUID unknown = UUID.randomUUID();
        when(parcels.findById(someoneElses.toSnapshot().id())).thenReturn(Optional.of(someoneElses));
        when(parcels.findById(merchantOrder.toSnapshot().id())).thenReturn(Optional.of(merchantOrder));
        when(parcels.findById(unknown)).thenReturn(Optional.empty());

        for (UUID id : List.of(someoneElses.toSnapshot().id(), merchantOrder.toSnapshot().id(), unknown)) {
            assertThatThrownBy(() -> service.pdfForCustomer(CUSTOMER_ID, id))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        // nothing is drawn for a receipt that isn't yours
        verify(renderer, never()).render(any());
    }

    @Test
    void thePdfIsNamedAfterTheTrackingNumber() {
        Parcel parcel = parcel(CUSTOMER_ID, PaymentMethod.ONLINE, BREAKDOWN);
        when(parcels.findById(parcel.toSnapshot().id())).thenReturn(Optional.of(parcel));
        when(renderer.render(any())).thenReturn(new byte[] {'%', 'P', 'D', 'F'});

        ReceiptService.ReceiptPdf pdf = service.pdfForCustomer(CUSTOMER_ID, parcel.toSnapshot().id());

        assertThat(pdf.fileName()).isEqualTo("SwiftDrop-receipt-SD2611003152.pdf");
        assertThat(pdf.content()).startsWith('%', 'P', 'D', 'F');
    }

    @Test
    void aFeeWithoutLinesOrWithUnreadableLinesIsOneDeliveryFeeLine() {
        BigDecimal total = new BigDecimal("750.00");

        for (String breakdown : new String[] {null, "", "not json", "[]", "[{\"component\":\"BASE\",\"amount\":\"x\"}]"}) {
            assertThat(service.lines(breakdown, total, 1))
                    .as("breakdown %s", breakdown)
                    .containsExactly(new Line("FEE", "Delivery fee", total));
        }
    }

    @Test
    void everyPricingComponentHasAPlainName() {
        assertThat(ReceiptService.label("BASE", 3)).isEqualTo("Base fee");
        assertThat(ReceiptService.label("WEIGHT", 3)).isEqualTo("Extra weight (2 kg)");
        assertThat(ReceiptService.label("WEIGHT", null)).isEqualTo("Extra weight");
        assertThat(ReceiptService.label("PICKUP", 3)).isEqualTo("Pickup");
        assertThat(ReceiptService.label("DELIVERY", 3)).isEqualTo("Drop-off");
        assertThat(ReceiptService.label("LINEHAUL", 3)).isEqualTo("Between branches");
        assertThat(ReceiptService.label("COD", 3)).isEqualTo("Cash on delivery handling");
        assertThat(ReceiptService.label("SOMETHING_NEW", 3)).isEqualTo("Other charges");
    }

    @Test
    void paidOrDueComesOnlyFromDeliverysFeePaymentStatus() {
        assertThat(ReceiptService.paymentStatus(FeePaymentStatus.CASH_COLLECTED)).isEqualTo(PaymentStatus.PAID);
        assertThat(ReceiptService.paymentStatus(FeePaymentStatus.ONLINE_VERIFIED)).isEqualTo(PaymentStatus.PAID);
        assertThat(ReceiptService.paymentStatus(FeePaymentStatus.AWAITING_CASH_COLLECTION))
                .isEqualTo(PaymentStatus.DUE);
        assertThat(ReceiptService.paymentStatus(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION))
                .isEqualTo(PaymentStatus.DUE);
        // Booked before payment methods: nothing is known, so no Paid/Due.
        assertThat(ReceiptService.paymentStatus(FeePaymentStatus.NOT_RECORDED)).isNull();
    }

    @Test
    void aRecordedOnlinePaymentShowsAsPaidOnTheReceipt() {
        Parcel parcel = parcel(CUSTOMER_ID, PaymentMethod.ONLINE, BREAKDOWN);
        UUID id = parcel.toSnapshot().id();
        FeePayment online = new FeePayment(new BigDecimal("980.00"), "LKR", PaymentMethod.ONLINE,
                FeePaymentSource.ONLINE, null, null, BOOKED_AT, "payments:" + UUID.randomUUID());
        when(parcels.findById(id)).thenReturn(Optional.of(parcel));
        when(deliveries.findById(id)).thenReturn(Optional.of(parcel.toSnapshot(online)));

        ReceiptResponse receipt = service.forCustomer(CUSTOMER_ID, id);

        assertThat(receipt.paymentMethod()).isEqualTo(PaymentMethod.ONLINE);
        assertThat(receipt.paymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void anOnlinePaymentNotRecordedYetIsDue() {
        Parcel parcel = parcel(CUSTOMER_ID, PaymentMethod.ONLINE, BREAKDOWN);
        UUID id = parcel.toSnapshot().id();
        when(parcels.findById(id)).thenReturn(Optional.of(parcel));
        when(deliveries.findById(id)).thenReturn(Optional.of(parcel.toSnapshot()));

        assertThat(service.forCustomer(CUSTOMER_ID, id).paymentStatus()).isEqualTo(PaymentStatus.DUE);
    }

    private static Parcel parcel(UUID customerId, PaymentMethod paymentMethod, String breakdown) {
        CreateDeliveryCommand command = new CreateDeliveryCommand(customerId, null, null, PICKUP, RECIPIENT,
                new BigDecimal("2.00"), null, null, new ParcelSize(30, 20, 15, false), paymentMethod);
        ParcelPrice price = new ParcelPrice(new BigDecimal("980.00"), "LKR", "BIKE", 2, 5, breakdown);
        return Parcel.create(UUID.randomUUID(), "SD2611003152", command, price, BOOKED_AT);
    }

    private static Parcel merchantOrder() {
        CreateDeliveryCommand command = new CreateDeliveryCommand(null, UUID.randomUUID(), "ORDER-1", PICKUP,
                RECIPIENT, new BigDecimal("1.00"), null, null, new ParcelSize(10, 10, 10, false),
                PaymentMethod.AT_DROP_OFF);
        return Parcel.create(UUID.randomUUID(), "SD2611000001", command,
                new ParcelPrice(new BigDecimal("500.00"), "LKR", "BIKE", 1, 5, null), BOOKED_AT);
    }
}
