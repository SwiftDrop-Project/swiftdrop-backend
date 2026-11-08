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
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeDeliveryApiTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z"); // 11:30 in Colombo

    private final UUID courier = UUID.randomUUID();
    private final UUID otherCourier = UUID.randomUUID();
    private FakeDeliveryApi fake;

    @BeforeEach
    void seed() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        fake = new FakeDeliveryApi(clock);
        FakeDeliverySeeder.Seeded seeded = FakeDeliverySeeder.seed(fake, new FakeAssignmentApi(clock), courier,
                otherCourier, NOW);
        assertThat(seeded).isEqualTo(new FakeDeliverySeeder.Seeded(9, 4));
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theDemoParcelsAreAtEachStageOfTheJourney() {
        assertThat(statuses(1, 2, 3)).containsOnly(ParcelStatus.CREATED);
        assertThat(parcel(1).pickup().city()).isEqualTo("Delgoda");
        assertThat(parcel(1).recipient().city()).isEqualTo("Deniyaya");
        assertThat(statuses(4, 5)).containsOnly(ParcelStatus.AT_BRANCH);
        assertThat(parcel(4).currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(statuses(6, 7, 8)).containsOnly(ParcelStatus.AT_BRANCH);
        assertThat(parcel(6).currentBranch()).isEqualTo(District.MATARA);
        assertThat(parcel(6).codAmount()).isNotNull();
        assertThat(parcel(9).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(parcel(9).currentBranch()).isNull();
    }

    @Test
    void aParcelAtMataraHasPassedThroughGampahaNewestFirst() {
        DeliverySnapshot atMatara = parcel(6);
        List<ParcelStatusEntry> history = fake.history(atMatara.id());

        assertThat(history).extracting(ParcelStatusEntry::status).containsExactly(ParcelStatus.AT_BRANCH,
                ParcelStatus.IN_TRANSIT, ParcelStatus.AT_BRANCH, ParcelStatus.PICKED_UP, ParcelStatus.CREATED);
        assertThat(history).extracting(ParcelStatusEntry::branch)
                .containsExactly(District.MATARA, null, District.GAMPAHA, null, null);
        assertThat(history.getFirst().occurredAt()).isEqualTo(atMatara.updatedAt());
        assertThat(history.getLast().occurredAt()).isEqualTo(atMatara.createdAt());
        assertThat(atMatara.version()).isEqualTo(5);
        assertThat(atMatara.trackingNumber()).matches("SD\\d{10}");
    }

    @Test
    void findsByTrackingNumberIgnoringSpacesAndCase() {
        assertThat(fake.findByTrackingNumber(" sd 2611 060001 ")).contains(parcel(1));
        assertThat(fake.findByTrackingNumber("SD2611069999")).isEmpty();
        assertThat(fake.findByTrackingNumber(null)).isEmpty();
    }

    @Test
    void findAllByIdLeavesUnknownIdsOut() {
        assertThat(fake.findAllById(List.of(parcel(1).id(), UUID.randomUUID(), parcel(2).id())))
                .containsExactlyInAnyOrder(parcel(1), parcel(2));
    }

    @Test
    void changeStatusMovesOneStepAndRecordsIt() {
        DeliverySnapshot pickup = parcel(1);

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
    void deliveredKeepsTheNameAndAFailedParcelGoesBackToTheBranchWithoutItsReason() {
        DeliverySnapshot first = parcel(6);
        fake.changeStatus(command(first.id(), ParcelStatus.OUT_FOR_DELIVERY, courier, "COURIER", null, null, null));
        DeliverySnapshot delivered = fake.changeStatus(command(first.id(), ParcelStatus.DELIVERED, courier, "COURIER",
                null, null, "Anoma Rathnayake"));
        assertThat(delivered.proofRecipientName()).isEqualTo("Anoma Rathnayake");
        assertThat(delivered.currentBranch()).isNull();

        DeliverySnapshot second = parcel(7);
        fake.changeStatus(command(second.id(), ParcelStatus.OUT_FOR_DELIVERY, courier, "COURIER", null, null, null));
        DeliverySnapshot failed = fake.changeStatus(command(second.id(), ParcelStatus.FAILED, courier, "COURIER",
                "Gate locked", FailureReason.OTHER, null));
        assertThat(failed.failureReason()).isEqualTo(FailureReason.OTHER);
        DeliverySnapshot returned = fake.changeStatus(new ChangeStatusCommand(second.id(), ParcelStatus.AT_BRANCH,
                courier, "COURIER", null, null, null, null, District.MATARA));
        assertThat(returned.currentBranch()).isEqualTo(District.MATARA);
        assertThat(returned.failureReason()).isNull();
        assertThat(fake.history(second.id())).extracting(ParcelStatusEntry::branch)
                .startsWith(District.MATARA, null, null);
        assertThat(fake.history(second.id()).get(1).failureReason()).isEqualTo(FailureReason.OTHER);
    }

    @Test
    void anyCourierMayChangeAParcelBecauseTheCallerChecksTheAssignment() {
        assertThat(fake.changeStatus(command(parcel(1).id(), ParcelStatus.PICKED_UP, otherCourier, "COURIER", null,
                null, null)).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(fake.changeStatus(command(parcel(2).id(), ParcelStatus.PICKED_UP, UUID.randomUUID(), "ADMIN",
                null, null, null)).status()).isEqualTo(ParcelStatus.PICKED_UP);
    }

    @Test
    void refusesWhatTheContractRefuses() {
        DeliverySnapshot pickup = parcel(1);

        assertCode(() -> fake.changeStatus(command(pickup.id(), ParcelStatus.DELIVERED, courier, "COURIER", null,
                null, "Nimal")), BusinessRuleException.class, ErrorCode.INVALID_STATUS_TRANSITION);
        assertCode(() -> fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, courier, "CUSTOMER",
                null, null, null)), ForbiddenException.class, ErrorCode.FORBIDDEN);
        assertCode(() -> fake.changeStatus(command(UUID.randomUUID(), ParcelStatus.PICKED_UP, courier, "COURIER",
                null, null, null)), NotFoundException.class, ErrorCode.NOT_FOUND);
        DeliverySnapshot atMatara = parcel(8);
        fake.changeStatus(command(atMatara.id(), ParcelStatus.OUT_FOR_DELIVERY, courier, "COURIER", null, null, null));
        assertThatThrownBy(() -> fake.changeStatus(new ChangeStatusCommand(atMatara.id(), ParcelStatus.DELIVERED,
                courier, "COURIER", null, null, null, UUID.randomUUID())))
                .isInstanceOf(FieldValidationException.class);

        assertThat(fake.findById(pickup.id()).orElseThrow().status()).isEqualTo(ParcelStatus.CREATED);
    }

    @Test
    void aChangeInARolledBackTransactionIsUndoneAndKeptOnCommit() {
        DeliverySnapshot pickup = parcel(1);
        List<ParcelStatusEntry> history = fake.history(pickup.id());

        TransactionSynchronizationManager.initSynchronization();
        fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, courier, "COURIER", null, null, null));
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(fake.findById(pickup.id())).contains(pickup);
        assertThat(fake.history(pickup.id())).isEqualTo(history);

        TransactionSynchronizationManager.initSynchronization();
        fake.changeStatus(command(pickup.id(), ParcelStatus.PICKED_UP, courier, "COURIER", null, null, null));
        finish(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(fake.findById(pickup.id()).orElseThrow().status()).isEqualTo(ParcelStatus.PICKED_UP);
    }

    @Test
    void everyOtherMethodIsNotSupported() {
        UUID id = UUID.randomUUID();
        List<Runnable> calls = List.of(
                () -> fake.create(null),
                () -> fake.assignCourier(id, id),
                () -> fake.findByCustomer(id, null, PageRequest.of(0, 20)),
                () -> fake.findByMerchant(id, null, PageRequest.of(0, 20)),
                () -> fake.findAtBranch(District.GAMPAHA),
                () -> fake.findUnassigned(),
                () -> fake.countActiveByCourier(),
                () -> fake.findForCourier(id, NOW),
                () -> fake.findFinishedForCourier(id, null, PageRequest.of(0, 20)),
                () -> fake.stats(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 6)),
                () -> fake.storeProofPhoto(id, id, "image/jpeg", new byte[0]),
                () -> fake.findProofPhoto(id));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("FakeDeliveryApi: not supported, wait for WP3");
        }
    }

    /** Demo parcel {@code number} (tracking number {@code SD26110600NN}). */
    private DeliverySnapshot parcel(int number) {
        return fake.findByTrackingNumber(String.format("SD26110600%02d", number)).orElseThrow();
    }

    private List<ParcelStatus> statuses(int... numbers) {
        return Arrays.stream(numbers).mapToObj(this::parcel).map(DeliverySnapshot::status).toList();
    }

    /** Ends the test "transaction" like a transaction manager would. */
    static void finish(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
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
