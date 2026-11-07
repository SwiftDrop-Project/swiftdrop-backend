// TEMPORARY - delete when WP3 implements DeliveryApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeDeliveryApiTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z"); // 11:30 in Colombo
    private static final Instant START_OF_TODAY = Instant.parse("2026-11-05T18:30:00Z");

    private final UUID courier = UUID.randomUUID();
    private final UUID otherCourier = UUID.randomUUID();
    private FakeDeliveryApi fake;

    @BeforeEach
    void seed() {
        fake = new FakeDeliveryApi(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(FakeDeliverySeeder.seed(fake, courier, otherCourier, NOW)).isEqualTo(8);
    }

    @Test
    void todayHasTheOpenParcelsAndTheOneDeliveredTodayButNotYesterdaysFailure() {
        List<DeliverySnapshot> today = fake.findForCourier(courier, START_OF_TODAY);

        assertThat(today).hasSize(6).allMatch(parcel -> courier.equals(parcel.assignedCourierId()));
        assertThat(today).extracting(DeliverySnapshot::status).containsExactlyInAnyOrder(
                ParcelStatus.CREATED, ParcelStatus.CREATED, ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT,
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED);
        assertThat(fake.findForCourier(otherCourier, START_OF_TODAY)).hasSize(1);
    }

    @Test
    void finishedParcelsAreNewestFirstAndCanBeFilteredByTrackingNumber() {
        Page<DeliverySnapshot> finished = fake.findFinishedForCourier(courier, null, PageRequest.of(0, 20));

        assertThat(finished.getTotalElements()).isEqualTo(2);
        assertThat(finished.getContent()).extracting(DeliverySnapshot::status)
                .containsExactly(ParcelStatus.DELIVERED, ParcelStatus.FAILED);
        String failedNumber = finished.getContent().get(1).trackingNumber();
        assertThat(fake.findFinishedForCourier(courier, failedNumber, PageRequest.of(0, 20)).getContent())
                .singleElement().extracting(DeliverySnapshot::status).isEqualTo(ParcelStatus.FAILED);
        assertThat(fake.findFinishedForCourier(courier, null, PageRequest.of(1, 1)).getContent())
                .singleElement().extracting(DeliverySnapshot::status).isEqualTo(ParcelStatus.FAILED);
    }

    @Test
    void seededParcelsHaveAFullHistoryNewestFirstAndEveryContactField() {
        DeliverySnapshot outForDelivery = only(ParcelStatus.OUT_FOR_DELIVERY);

        assertThat(fake.history(outForDelivery.id())).extracting(ParcelStatusEntry::status).containsExactly(
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.IN_TRANSIT, ParcelStatus.PICKED_UP, ParcelStatus.CREATED);
        assertThat(outForDelivery.version()).isEqualTo(4);
        assertThat(outForDelivery.codAmount()).isNotNull();
        assertThat(outForDelivery.trackingNumber()).matches("SD\\d{10}");
        assertThat(LocalDate.ofInstant(only(ParcelStatus.FAILED).updatedAt(), ZoneId.of("Asia/Colombo")))
                .isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void changeStatusMovesOneStepAndRecordsIt() {
        DeliverySnapshot pickup = fake.findForCourier(courier, START_OF_TODAY).stream()
                .filter(parcel -> parcel.status() == ParcelStatus.CREATED).findFirst().orElseThrow();

        DeliverySnapshot pickedUp = fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, courier, "COURIER",
                "Collected", null, null));

        assertThat(pickedUp.status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(pickedUp.version()).isEqualTo(pickup.version() + 1);
        assertThat(pickedUp.updatedAt()).isEqualTo(NOW);
        assertThat(fake.findById(pickup.id())).contains(pickedUp);
        ParcelStatusEntry latest = fake.history(pickup.id()).getFirst();
        assertThat(latest.status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(latest.note()).isEqualTo("Collected");
        assertThat(latest.actorRole()).isEqualTo("COURIER");
        assertThat(latest.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void deliveredKeepsTheRecipientNameAndFailedKeepsTheReason() {
        DeliverySnapshot parcel = only(ParcelStatus.OUT_FOR_DELIVERY);
        DeliverySnapshot delivered = fake.changeStatus(command(parcel.id(), ParcelStatus.DELIVERED, courier, "COURIER",
                null, null, "Kasun Wickramasinghe"));
        assertThat(delivered.proofRecipientName()).isEqualTo("Kasun Wickramasinghe");
        assertThat(delivered.failureReason()).isNull();

        DeliverySnapshot inTransit = only(ParcelStatus.IN_TRANSIT);
        fake.changeStatus(command(inTransit.id(), ParcelStatus.OUT_FOR_DELIVERY, courier, "COURIER", null, null, null));
        DeliverySnapshot failed = fake.changeStatus(command(inTransit.id(), ParcelStatus.FAILED, courier, "COURIER",
                "Gate locked", FailureReason.OTHER, null));
        assertThat(failed.failureReason()).isEqualTo(FailureReason.OTHER);
        assertThat(failed.proofRecipientName()).isNull();
        assertThat(fake.history(inTransit.id()).getFirst().failureReason()).isEqualTo(FailureReason.OTHER);
    }

    @Test
    void refusesWhatTheContractRefuses() {
        DeliverySnapshot pickup = fake.findForCourier(courier, START_OF_TODAY).stream()
                .filter(parcel -> parcel.status() == ParcelStatus.CREATED).findFirst().orElseThrow();

        assertCode(() -> fake.changeStatus(command(pickup.id(), ParcelStatus.DELIVERED, courier, "COURIER", null,
                null, "Nimal")), BusinessRuleException.class, ErrorCode.INVALID_STATUS_TRANSITION);
        assertCode(() -> fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, otherCourier, "COURIER",
                null, null, null)), ForbiddenException.class, ErrorCode.FORBIDDEN);
        assertCode(() -> fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, courier, "CUSTOMER",
                null, null, null)), ForbiddenException.class, ErrorCode.FORBIDDEN);
        assertCode(() -> fake.changeStatus(command(UUID.randomUUID(), ParcelStatus.PICKED_UP, courier, "COURIER",
                null, null, null)), NotFoundException.class, ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> fake.changeStatus(new ChangeStatusCommand(only(ParcelStatus.OUT_FOR_DELIVERY).id(),
                ParcelStatus.DELIVERED, courier, "COURIER", null, null, null, UUID.randomUUID())))
                .isInstanceOf(FieldValidationException.class);

        assertThat(fake.findById(pickup.id()).orElseThrow().status()).isEqualTo(ParcelStatus.CREATED);
    }

    @Test
    void anAdminMayChangeAParcelAssignedToSomeoneElse() {
        DeliverySnapshot pickup = fake.findForCourier(courier, START_OF_TODAY).stream()
                .filter(parcel -> parcel.status() == ParcelStatus.CREATED).findFirst().orElseThrow();

        assertThat(fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, UUID.randomUUID(), "ADMIN", null,
                null, null)).status()).isEqualTo(ParcelStatus.PICKED_UP);
    }

    @Test
    void everyOtherMethodIsNotSupported() {
        UUID id = UUID.randomUUID();
        List<Runnable> calls = List.of(
                () -> fake.create(null),
                () -> fake.assignCourier(id, id),
                () -> fake.findByTrackingNumber("SD2611060001"),
                () -> fake.findByCustomer(id, null, PageRequest.of(0, 20)),
                () -> fake.findByMerchant(id, null, PageRequest.of(0, 20)),
                () -> fake.findUnassigned(),
                () -> fake.countActiveByCourier(),
                () -> fake.stats(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 6)),
                () -> fake.storeProofPhoto(id, id, "image/jpeg", new byte[0]),
                () -> fake.findProofPhoto(id));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("FakeDeliveryApi: not supported, wait for WP3");
        }
    }

    private DeliverySnapshot only(ParcelStatus status) {
        return fake.findForCourier(courier, Instant.EPOCH).stream()
                .filter(parcel -> parcel.status() == status)
                .findFirst().orElseThrow();
    }

    private static ChangeStatusCommand command(UUID parcelId, ParcelStatus to, UUID actorId, String role, String note,
                                               FailureReason reason, String recipientName) {
        return new ChangeStatusCommand(parcelId, to, actorId, role, note, reason, recipientName, null);
    }

    private static void assertCode(Runnable call, Class<? extends ApiException> type, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(type)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(code));
    }
}
