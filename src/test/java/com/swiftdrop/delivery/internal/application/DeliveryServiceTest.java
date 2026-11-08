package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ParcelStatusHistory;
import com.swiftdrop.delivery.internal.domain.TrackingNumberGenerator;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.delivery.internal.persistence.ParcelStatusHistoryRepository;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.AT_BRANCH;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");
    private static final UUID COURIER_ID = UUID.randomUUID();
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
    void methodsOfLaterFeaturesAnswerServiceUnavailable() {
        // the same 503 the courier module gives when no DeliveryApi exists
        assertThatThrownBy(service::findUnassigned)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    // ---- Reading parcels ----

    @Test
    void finishedFollowsParcelStatus() {
        // a FAILED parcel goes back to a branch, so only DELIVERED is finished
        assertThat(DeliveryService.FINISHED).containsExactly(DELIVERED);
    }

    @Test
    void findByIdGivesTheSnapshotOrNothing() {
        Parcel parcel = aParcel();
        when(parcels.findById(parcel.getId())).thenReturn(Optional.of(parcel));

        assertThat(service.findById(parcel.getId()))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.trackingNumber()).isEqualTo("SD2611000777"));
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void historyKeepsTheRepositoryOrderNewestFirst() {
        Parcel parcel = aParcel();
        ParcelStatusHistory created = ParcelStatusHistory.firstEntry(UUID.randomUUID(), parcel);
        var pickUp = new ChangeStatusCommand(parcel.getId(), PICKED_UP, COURIER_ID, "COURIER", "At the shop", null,
                null, null);
        ParcelStatusHistory pickedUp = ParcelStatusHistory.forChange(UUID.randomUUID(), CREATED, pickUp, NOW);
        when(statusHistory.findByParcelIdOrderByOccurredAtDesc(parcel.getId())).thenReturn(List.of(pickedUp, created));

        List<ParcelStatusEntry> history = service.history(parcel.getId());

        assertThat(history).extracting(ParcelStatusEntry::status).containsExactly(PICKED_UP, CREATED);
        assertThat(history.get(0).note()).isEqualTo("At the shop");
        assertThat(service.history(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAllByIdGivesTheParcelsItFindsAndLeavesOutUnknownIds() {
        Parcel parcel = aParcel();
        List<UUID> ids = List.of(parcel.getId(), UUID.randomUUID());
        when(parcels.findAllById(ids)).thenReturn(List.of(parcel));

        assertThat(service.findAllById(ids))
                .extracting(DeliverySnapshot::id)
                .containsExactly(parcel.getId());
    }

    @Test
    void findAtBranchAsksForTheParcelsWaitingThere() {
        Parcel parcel = aParcel();
        when(parcels.findWaitingAtBranch(District.GAMPAHA)).thenReturn(List.of(parcel));

        assertThat(service.findAtBranch(District.GAMPAHA))
                .extracting(DeliverySnapshot::id)
                .containsExactly(parcel.getId());
        assertThat(service.findAtBranch(District.MATARA)).isEmpty();
    }

    @Test
    void findForCourierAsksForUnfinishedAndRecentlyFinishedParcels() {
        Instant startOfDay = NOW.minusSeconds(3600);
        Parcel parcel = aParcel();
        when(parcels.findForCourier(COURIER_ID, DeliveryService.FINISHED, startOfDay)).thenReturn(List.of(parcel));

        assertThat(service.findForCourier(COURIER_ID, startOfDay))
                .extracting(DeliverySnapshot::id)
                .containsExactly(parcel.getId());
    }

    @Test
    void finishedForCourierFiltersByTrackingNumberOnlyWhenOneIsGiven() {
        Pageable page = PageRequest.of(0, 20);
        var onePage = new PageImpl<>(List.of(aParcel()), page, 1);
        when(parcels.findByAssignedCourierIdAndStatusInOrderByStatusChangedAtDesc(COURIER_ID,
                DeliveryService.FINISHED, page)).thenReturn(onePage);
        when(parcels.findByAssignedCourierIdAndStatusInAndTrackingNumberOrderByStatusChangedAtDesc(COURIER_ID,
                DeliveryService.FINISHED, "SD2611000777", page)).thenReturn(onePage);

        assertThat(service.findFinishedForCourier(COURIER_ID, null, page).getContent()).hasSize(1);
        assertThat(service.findFinishedForCourier(COURIER_ID, "SD2611000777", page).getContent()).hasSize(1);

        verify(parcels).findByAssignedCourierIdAndStatusInOrderByStatusChangedAtDesc(COURIER_ID,
                DeliveryService.FINISHED, page);
        verify(parcels).findByAssignedCourierIdAndStatusInAndTrackingNumberOrderByStatusChangedAtDesc(COURIER_ID,
                DeliveryService.FINISHED, "SD2611000777", page);
    }

    // ---- Changing status ----

    @Test
    void aPickUpSavesTheParcelThenItsHistoryRowAndTheEvent() {
        Parcel parcel = stored(aParcel());

        DeliverySnapshot snapshot = service.changeStatus(new ChangeStatusCommand(parcel.getId(), PICKED_UP,
                COURIER_ID, "COURIER", "At the shop", null, null, null));

        assertThat(snapshot.status()).isEqualTo(PICKED_UP);
        // flushed first, so the event carries the parcel's new version
        InOrder order = inOrder(parcels, statusHistory, outbox);
        order.verify(parcels).saveAndFlush(parcel);
        ArgumentCaptor<ParcelStatusHistory> row = ArgumentCaptor.forClass(ParcelStatusHistory.class);
        order.verify(statusHistory).save(row.capture());
        ArgumentCaptor<ParcelStatusChanged> event = ArgumentCaptor.forClass(ParcelStatusChanged.class);
        order.verify(outbox).write(event.capture());

        assertThat(row.getValue().getParcelId()).isEqualTo(parcel.getId());
        assertThat(row.getValue().getFromStatus()).isEqualTo(CREATED);
        assertThat(row.getValue().getToStatus()).isEqualTo(PICKED_UP);
        assertThat(row.getValue().getActorId()).isEqualTo(COURIER_ID);
        assertThat(row.getValue().getActorRole()).isEqualTo("COURIER");
        assertThat(row.getValue().getNote()).isEqualTo("At the shop");
        assertThat(row.getValue().getOccurredAt()).isEqualTo(NOW);

        assertThat(event.getValue().parcelId()).isEqualTo(parcel.getId());
        assertThat(event.getValue().parcelVersion()).isEqualTo(snapshot.version());
        assertThat(event.getValue().trackingNumber()).isEqualTo("SD2611000777");
        assertThat(event.getValue().customerId()).isEqualTo(parcel.getCustomerId());
        assertThat(event.getValue().fromStatus()).isEqualTo(CREATED);
        assertThat(event.getValue().toStatus()).isEqualTo(PICKED_UP);
        assertThat(event.getValue().actorId()).isEqualTo(COURIER_ID);
        assertThat(event.getValue().actorRole()).isEqualTo("COURIER");
        assertThat(event.getValue().note()).isEqualTo("At the shop");
        assertThat(event.getValue().failureReason()).isNull();
        assertThat(event.getValue().branch()).isNull();
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void arrivingAtABranchPutsTheBranchOnTheParcelAndTheEvent() {
        Parcel parcel = stored(aParcelMovedTo(PICKED_UP));

        DeliverySnapshot snapshot = service.changeStatus(new ChangeStatusCommand(parcel.getId(), AT_BRANCH,
                COURIER_ID, "COURIER", null, null, null, null, District.GAMPAHA));

        assertThat(snapshot.status()).isEqualTo(AT_BRANCH);
        assertThat(snapshot.currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(writtenEvent().branch()).isEqualTo(District.GAMPAHA);
    }

    @Test
    void aFailedDeliveryNamesTheReasonInTheEvent() {
        Parcel parcel = stored(aParcelMovedTo(PICKED_UP, AT_BRANCH, OUT_FOR_DELIVERY));

        DeliverySnapshot snapshot = service.changeStatus(new ChangeStatusCommand(parcel.getId(), FAILED,
                COURIER_ID, "COURIER", "Nobody at home", FailureReason.RECIPIENT_UNAVAILABLE, null, null));

        assertThat(snapshot.status()).isEqualTo(FAILED);
        assertThat(snapshot.failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        ParcelStatusChanged event = writtenEvent();
        assertThat(event.fromStatus()).isEqualTo(OUT_FOR_DELIVERY);
        assertThat(event.failureReason()).isEqualTo("RECIPIENT_UNAVAILABLE");
        assertThat(event.branch()).isNull();
    }

    @Test
    void aDeliveryKeepsTheRecipientName() {
        Parcel parcel = stored(aParcelMovedTo(PICKED_UP, AT_BRANCH, OUT_FOR_DELIVERY));

        DeliverySnapshot snapshot = service.changeStatus(new ChangeStatusCommand(parcel.getId(), DELIVERED,
                COURIER_ID, "COURIER", null, null, "Kamala Silva", null));

        assertThat(snapshot.status()).isEqualTo(DELIVERED);
        assertThat(snapshot.proofRecipientName()).isEqualTo("Kamala Silva");
        assertThat(snapshot.hasProofPhoto()).isFalse();
    }

    @Test
    void anAdminMayChangeTheStatusToo() {
        Parcel parcel = stored(aParcel());
        UUID adminId = UUID.randomUUID();

        DeliverySnapshot snapshot = service.changeStatus(new ChangeStatusCommand(parcel.getId(), PICKED_UP,
                adminId, "ADMIN", null, null, null, null));

        assertThat(snapshot.status()).isEqualTo(PICKED_UP);
        assertThat(writtenEvent().actorRole()).isEqualTo("ADMIN");
    }

    @Test
    void anUnknownParcelIsNotFound() {
        assertThatThrownBy(() -> service.changeStatus(pickUp(UUID.randomUUID(), "COURIER")))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNothingSaved();
    }

    @Test
    void onlyCouriersAndAdminsMayChangeTheStatus() {
        Parcel parcel = stored(aParcel());

        assertThatThrownBy(() -> service.changeStatus(pickUp(parcel.getId(), "CUSTOMER")))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThat(parcel.getStatus()).isEqualTo(CREATED);
        verifyNothingSaved();
    }

    @Test
    void aMoveTheCurrentStatusDoesNotAllowIsAConflict() {
        Parcel parcel = stored(aParcel());

        assertThatThrownBy(() -> service.changeStatus(new ChangeStatusCommand(parcel.getId(), DELIVERED,
                COURIER_ID, "COURIER", null, null, "Kamala Silva", null)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        verifyNothingSaved();
    }

    @Test
    void aProofPhotoIsRefusedUntilPhotosCanBeStored() {
        Parcel parcel = stored(aParcelMovedTo(PICKED_UP, AT_BRANCH, OUT_FOR_DELIVERY));

        assertThatThrownBy(() -> service.changeStatus(new ChangeStatusCommand(parcel.getId(), DELIVERED,
                COURIER_ID, "COURIER", null, null, "Kamala Silva", UUID.randomUUID())))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("proofPhotoId"));

        verifyNothingSaved();
    }

    @Test
    void aChangeSomeoneElseMadeInTheMeantimeIsAConflict() {
        Parcel parcel = stored(aParcel());
        when(parcels.saveAndFlush(parcel))
                .thenThrow(new ObjectOptimisticLockingFailureException(Parcel.class, parcel.getId()));

        assertThatThrownBy(() -> service.changeStatus(pickUp(parcel.getId(), "COURIER")))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        verify(statusHistory, never()).save(any());
        verify(outbox, never()).write(any());
    }

    private Parcel stored(Parcel parcel) {
        when(parcels.findById(parcel.getId())).thenReturn(Optional.of(parcel));
        return parcel;
    }

    private ParcelStatusChanged writtenEvent() {
        ArgumentCaptor<ParcelStatusChanged> event = ArgumentCaptor.forClass(ParcelStatusChanged.class);
        verify(outbox).write(event.capture());
        return event.getValue();
    }

    private void verifyNothingSaved() {
        verify(parcels, never()).saveAndFlush(any());
        verify(statusHistory, never()).save(any());
        verify(outbox, never()).write(any());
    }

    private static ChangeStatusCommand pickUp(UUID parcelId, String actorRole) {
        return new ChangeStatusCommand(parcelId, PICKED_UP, COURIER_ID, actorRole, null, null, null, null);
    }

    /** A new parcel moved through {@code steps}; AT_BRANCH is the Colombo branch. */
    private static Parcel aParcelMovedTo(ParcelStatus... steps) {
        Parcel parcel = aParcel();
        for (ParcelStatus step : steps) {
            District branch = step == AT_BRANCH ? District.COLOMBO : null;
            parcel.changeStatus(new ChangeStatusCommand(parcel.getId(), step, COURIER_ID, "COURIER", null, null,
                    null, null, branch), NOW);
        }
        return parcel;
    }

    private static Parcel aParcel() {
        return Parcel.create(UUID.randomUUID(), "SD2611000777", customerBooking(UUID.randomUUID()),
                new BigDecimal("450.00"), "LKR", NOW);
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
