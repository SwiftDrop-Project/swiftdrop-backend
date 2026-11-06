package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ParcelStatusHistory;
import com.swiftdrop.delivery.internal.domain.TrackingNumberGenerator;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.delivery.internal.persistence.ParcelStatusHistoryRepository;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    private final ParcelRepository parcels = mock(ParcelRepository.class);
    private final ParcelStatusHistoryRepository statusHistory = mock(ParcelStatusHistoryRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final FeeQuoter feeQuoter = mock(FeeQuoter.class);
    private final TrackingNumberGenerator trackingNumbers = mock(TrackingNumberGenerator.class);
    private final DeliveryService service = new DeliveryService(parcels, statusHistory, outbox, feeQuoter,
            trackingNumbers, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void aFeeAndAFreeTrackingNumber() {
        when(feeQuoter.quote(any())).thenReturn(new FeeQuoter.Fee(new BigDecimal("450.00"), "LKR"));
        when(trackingNumbers.next(NOW)).thenReturn("SD2611004817");
    }

    @Test
    void aCustomerBookingSavesTheParcelItsFirstHistoryRowAndTheCreatedEvent() {
        UUID customerId = UUID.randomUUID();

        DeliverySnapshot snapshot = service.create(customerBooking(customerId));

        ArgumentCaptor<Parcel> parcel = ArgumentCaptor.forClass(Parcel.class);
        verify(parcels).save(parcel.capture());
        assertThat(parcel.getValue().getStatus()).isEqualTo(CREATED);
        assertThat(parcel.getValue().getTrackingNumber()).isEqualTo("SD2611004817");
        assertThat(parcel.getValue().getFeeAmount()).isEqualByComparingTo("450.00");
        assertThat(parcel.getValue().getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<ParcelStatusHistory> firstRow = ArgumentCaptor.forClass(ParcelStatusHistory.class);
        verify(statusHistory).save(firstRow.capture());
        assertThat(firstRow.getValue().getParcelId()).isEqualTo(parcel.getValue().getId());
        assertThat(firstRow.getValue().getActorId()).isEqualTo(customerId);

        ArgumentCaptor<ParcelStatusChanged> event = ArgumentCaptor.forClass(ParcelStatusChanged.class);
        verify(outbox).write(event.capture());
        assertThat(event.getValue().parcelId()).isEqualTo(parcel.getValue().getId());
        assertThat(event.getValue().fromStatus()).isNull();
        assertThat(event.getValue().toStatus()).isEqualTo(CREATED);
        assertThat(event.getValue().parcelVersion()).isZero();
        assertThat(event.getValue().actorId()).isEqualTo(customerId);
        assertThat(event.getValue().actorRole()).isEqualTo("CUSTOMER");
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);

        assertThat(snapshot.trackingNumber()).isEqualTo("SD2611004817");
        assertThat(snapshot.feeAmount()).isEqualByComparingTo("450.00");
        assertThat(snapshot.currency()).isEqualTo("LKR");
    }

    @Test
    void aTakenTrackingNumberIsReplacedByAFreeOne() {
        when(trackingNumbers.next(NOW)).thenReturn("SD2611000001", "SD2611000002");
        when(parcels.existsByTrackingNumber("SD2611000001")).thenReturn(true);

        DeliverySnapshot snapshot = service.create(customerBooking(UUID.randomUUID()));

        assertThat(snapshot.trackingNumber()).isEqualTo("SD2611000002");
    }

    @Test
    void itGivesUpWhenNoTrackingNumberIsFree() {
        when(parcels.existsByTrackingNumber(any())).thenReturn(true);

        assertThatIllegalStateException().isThrownBy(() -> service.create(customerBooking(UUID.randomUUID())));

        verify(trackingNumbers, times(DeliveryService.TRACKING_NUMBER_TRIES)).next(NOW);
        verify(parcels, never()).save(any());
        verify(outbox, never()).write(any());
    }

    @Test
    void aMerchantOrderIsMadeByTheMerchant() {
        UUID merchantId = UUID.randomUUID();

        DeliverySnapshot snapshot = service.create(merchantOrder(merchantId, "ORDER-1001"));

        assertThat(snapshot.merchantId()).isEqualTo(merchantId);
        assertThat(snapshot.customerId()).isNull();
        assertThat(snapshot.merchantOrderRef()).isEqualTo("ORDER-1001");
        ArgumentCaptor<ParcelStatusChanged> event = ArgumentCaptor.forClass(ParcelStatusChanged.class);
        verify(outbox).write(event.capture());
        assertThat(event.getValue().actorId()).isEqualTo(merchantId);
        assertThat(event.getValue().actorRole()).isEqualTo("MERCHANT");
    }

    @Test
    void aMerchantRetryWithTheSameOrderRefReturnsTheFirstParcel() {
        UUID merchantId = UUID.randomUUID();
        Parcel first = Parcel.create(UUID.randomUUID(), "SD2611000777", merchantOrder(merchantId, "ORDER-1001"),
                new BigDecimal("450.00"), "LKR", NOW.minusSeconds(60));
        when(parcels.findByMerchantIdAndMerchantOrderRef(merchantId, "ORDER-1001")).thenReturn(Optional.of(first));

        DeliverySnapshot snapshot = service.create(merchantOrder(merchantId, "ORDER-1001"));

        assertThat(snapshot.id()).isEqualTo(first.getId());
        assertThat(snapshot.trackingNumber()).isEqualTo("SD2611000777");
        verify(parcels, never()).save(any());
        verify(statusHistory, never()).save(any());
        verify(outbox, never()).write(any());
        verifyNoInteractions(feeQuoter);
    }

    @Test
    void methodsOfLaterFeaturesAreNotBuiltYet() {
        assertThatThrownBy(() -> service.findById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Not built yet: DeliveryApi.findById");
    }

    private static CreateDeliveryCommand customerBooking(UUID customerId) {
        return new CreateDeliveryCommand(customerId, null, null, PICKUP, RECIPIENT, new BigDecimal("2.00"), null,
                null);
    }

    private static CreateDeliveryCommand merchantOrder(UUID merchantId, String orderRef) {
        return new CreateDeliveryCommand(null, merchantId, orderRef, PICKUP, RECIPIENT, new BigDecimal("1.50"),
                new BigDecimal("2500.00"), null);
    }
}
