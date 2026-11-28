package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.RecordFeeCollectionCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery fee on the courier API (courier module only: delivery and dispatch are mocks, see
 * {@link CourierApiTest}): {@code deliveryFee} with its payment status and {@code collectOn}, and the
 * {@code feeCollected} confirmation on PICKED_UP (AT_PICKUP) and DELIVERED (AT_DROP_OFF). The real modules together,
 * the database's uniqueness, races and rollbacks are in {@code CourierFeeCollectionTest}.
 */
class CourierDeliveryFeeTest extends CourierApiTest {

    private static final BigDecimal FEE = new BigDecimal("522.60");
    private static final BigDecimal COD = new BigDecimal("2450.00");

    private DeliverySnapshot booked(UUID courierId, ParcelStatus status, BigDecimal cod, PaymentMethod method) {
        return given(withFee(parcel(courierId, status), FEE, "LKR", cod, method));
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    private static FeePayment cash(DeliverySnapshot parcel) {
        return new FeePayment(parcel.feeAmount(), parcel.currency(), parcel.paymentMethod(),
                FeePaymentSource.COURIER_CASH, UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }

    // ---- What the courier sees ----

    @Test
    void todayShowsTheFeeItsStatusAndWhereItIsCollected() throws Exception {
        Courier courier = registerCourier();
        booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_PICKUP);

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deliveryFee.amount").value(522.60))
                .andExpect(jsonPath("$.items[0].deliveryFee.currency").value("LKR"))
                .andExpect(jsonPath("$.items[0].deliveryFee.paymentMethod").value("AT_PICKUP"))
                .andExpect(jsonPath("$.items[0].deliveryFee.status").value("AWAITING_CASH_COLLECTION"))
                .andExpect(jsonPath("$.items[0].deliveryFee.collectedAt").isEmpty())
                .andExpect(jsonPath("$.items[0].deliveryFee.collectOn").value("PICKED_UP"))
                .andExpect(jsonPath("$.items[0].codAmount").isEmpty())
                .andExpect(jsonPath("$.items[0].feeAmount").doesNotExist());
    }

    @Test
    void anAtDropOffFeeIsCollectedWithDeliveredOnlyOnceTheParcelIsOutForDelivery() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot out = booked(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, COD, PaymentMethod.AT_DROP_OFF);
        DeliverySnapshot atBranch = booked(courier.id(), ParcelStatus.AT_BRANCH, COD, PaymentMethod.AT_DROP_OFF);

        mvc.perform(get(BASE + "/{id}", out.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.collectOn").value("DELIVERED"))
                .andExpect(jsonPath("$.codAmount").value(2450.00))
                .andExpect(jsonPath("$.deliveryFee.amount").value(522.60));
        // Not taken out yet: nothing is collected in this step's next change (OUT_FOR_DELIVERY).
        mvc.perform(get(BASE + "/{id}", atBranch.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_CASH_COLLECTION"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
    }

    @Test
    void onlineIsAwaitingVerificationAndOlderParcelsAreNotRecorded() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot online = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.ONLINE);
        DeliverySnapshot legacy = given(parcel(courier.id(), ParcelStatus.CREATED));

        mvc.perform(get(BASE + "/{id}", online.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.paymentMethod").value("ONLINE"))
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_ONLINE_VERIFICATION"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
        mvc.perform(get(BASE + "/{id}", legacy.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.amount").value(450.00))
                .andExpect(jsonPath("$.deliveryFee.paymentMethod").isEmpty())
                .andExpect(jsonPath("$.deliveryFee.status").value("NOT_RECORDED"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
    }

    @Test
    void aVerifiedOnlinePaymentIsShownAsVerified() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = withFee(parcel(courier.id(), ParcelStatus.CREATED), FEE, "LKR", null,
                PaymentMethod.ONLINE);
        given(withPayment(parcel, new FeePayment(FEE, "LKR", PaymentMethod.ONLINE, FeePaymentSource.ONLINE, null, null,
                Instant.parse("2026-11-27T10:00:00Z"))));

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.status").value("ONLINE_VERIFIED"))
                .andExpect(jsonPath("$.deliveryFee.collectedAt").value("2026-11-27T10:00:00Z"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
    }

    @Test
    void aFeeCollectedAtPickupShowsAsCollectedOnTheLaterDelivery() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = withFee(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY), FEE, "LKR", COD,
                PaymentMethod.AT_PICKUP);
        given(withPayment(parcel, cash(parcel)));

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.collectedAt").isNotEmpty())
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty())
                // the cash on delivery is still collected, as before
                .andExpect(jsonPath("$.codAmount").value(2450.00));
    }

    @Test
    void historyEntriesShowThePaymentReadOnly() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot delivered = withFee(parcel(courier.id(), ParcelStatus.DELIVERED), FEE, "LKR", COD,
                PaymentMethod.AT_DROP_OFF);
        given(withPayment(delivered, cash(delivered)));

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()))
                .andExpect(jsonPath("$.content[0].deliveryFee.status").value("CASH_COLLECTED"))
                .andExpect(jsonPath("$.content[0].deliveryFee.collectOn").isEmpty());
        mvc.perform(get(BASE + "/{id}/history/{assignmentId}", delivered.id(), taskOf(delivered.id()).id())
                        .cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty())
                .andExpect(jsonPath("$.nextStatuses").isEmpty());
    }

    // ---- Collecting at pickup (AT_PICKUP) ----

    @Test
    void pickingUpAnAtPickupParcelNeedsTheFeeConfirmed() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_PICKUP);

        postStatus(parcel.id(), body("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.feeCollected")
                        .value("Confirm that you collected the delivery fee of LKR 522.60 in cash."));
        postStatus(parcel.id(), body("status", "PICKED_UP", "feeCollected", false), courier.cookie())
                .andExpect(status().isBadRequest());

        verify(delivery, never()).changeStatus(any());
        verify(delivery, never()).recordFeeCollection(any());
        assertThat(current(parcel.id()).status()).isEqualTo(ParcelStatus.CREATED);
    }

    @Test
    void confirmingThePickupRecordsTheFeeForThisCourierAndTask() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_PICKUP);
        UUID task = taskOf(parcel.id()).id();

        postStatus(parcel.id(), body("status", "PICKED_UP", "feeCollected", true), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.task.outcome").value("COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.collectedAt").isNotEmpty())
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());

        ArgumentCaptor<RecordFeeCollectionCommand> recorded = ArgumentCaptor.forClass(RecordFeeCollectionCommand.class);
        verify(delivery).recordFeeCollection(recorded.capture());
        assertThat(recorded.getValue()).isEqualTo(new RecordFeeCollectionCommand(parcel.id(), courier.id(), task));
    }

    // ---- Collecting at delivery (AT_DROP_OFF) ----

    @Test
    void deliveringAnAtDropOffParcelNeedsTheFeeConfirmedAsWellAsTheProof() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = booked(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, COD, PaymentMethod.AT_DROP_OFF);

        postStatus(parcel.id(), body("status", "DELIVERED", "recipientName", "Nimal Perera", "proofPhotoId", PROOF_ID),
                courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").exists());
        // The proof is still required with the fee.
        postStatus(parcel.id(), body("status", "DELIVERED", "recipientName", "Nimal Perera", "feeCollected", true),
                courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.proofPhotoId").exists());
        verify(delivery, never()).changeStatus(any());

        postStatus(parcel.id(), body("status", "DELIVERED", "recipientName", "Nimal Perera", "proofPhotoId", PROOF_ID,
                "feeCollected", true), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"))
                // COD unchanged and apart from the fee
                .andExpect(jsonPath("$.codAmount").value(2450.00))
                .andExpect(jsonPath("$.deliveryFee.amount").value(522.60));
        verify(delivery).recordFeeCollection(any());
    }

    @Test
    void anAtDropOffFeeWithoutCashOnDeliveryIsCollectedTheSameWay() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = booked(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, null, PaymentMethod.AT_DROP_OFF);

        postStatus(parcel.id(), body("status", "DELIVERED", "recipientName", "Nimal Perera", "proofPhotoId", PROOF_ID,
                "feeCollected", true), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codAmount").isEmpty())
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"));
    }

    // ---- Never at the wrong step ----

    @Test
    void feeCollectedIsRefusedWithTransfersHandoversFailuresAndReturns() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot pending = booked(courier.id(), ParcelStatus.AT_BRANCH, null, PaymentMethod.AT_DROP_OFF);
        DeliverySnapshot out = booked(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, null, PaymentMethod.AT_DROP_OFF);
        DeliverySnapshot failed = booked(courier.id(), ParcelStatus.FAILED, null, PaymentMethod.AT_DROP_OFF);
        DeliverySnapshot collected = booked(courier.id(), ParcelStatus.PICKED_UP, null, PaymentMethod.AT_PICKUP);

        String onlyThere = "Only when you pick up or deliver the parcel.";
        postStatus(pending.id(), body("status", "OUT_FOR_DELIVERY", "feeCollected", true), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").value(onlyThere));
        postStatus(out.id(), body("status", "FAILED", "failureReason", "RECIPIENT_REFUSED", "feeCollected", true),
                courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").value(onlyThere));
        postStatus(failed.id(), body("status", "AT_BRANCH", "receivedBy", "S. Silva", "feeCollected", true),
                courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").value(onlyThere));
        postStatus(collected.id(), body("status", "AT_BRANCH", "receivedBy", "S. Silva", "feeCollected", true),
                courier.cookie())
                .andExpect(status().isBadRequest());
        verify(delivery, never()).changeStatus(any());
        verify(delivery, never()).recordFeeCollection(any());
    }

    @Test
    void aFailedDeliveryAndItsReturnNeedNoFeeAndRecordNone() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot out = booked(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, COD, PaymentMethod.AT_DROP_OFF);

        postStatus(out.id(), body("status", "FAILED", "failureReason", "RECIPIENT_REFUSED"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_CASH_COLLECTION"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
        postStatus(out.id(), body("status", "AT_BRANCH", "receivedBy", "S. Silva"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_CASH_COLLECTION"));
        verify(delivery, never()).recordFeeCollection(any());
    }

    @Test
    void aSenderWhoDoesNotPayIsUnableToCollectAndNothingIsRecorded() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_PICKUP);

        postUnableToCollect(parcel.id(), Map.of("problem", "OTHER", "note", "Sender refused to pay the fee"),
                courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.outcome").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_CASH_COLLECTION"));
        verify(delivery, never()).recordFeeCollection(any());
    }

    @Test
    void theFeeIsOnlyCollectedInTheStepThatOwnsIt() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        // An AT_DROP_OFF fee isn't taken at pickup; an AT_PICKUP fee (not collected) isn't taken at delivery.
        DeliverySnapshot dropOff = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_DROP_OFF);
        DeliverySnapshot pickupFee = booked(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, null, PaymentMethod.AT_PICKUP);

        postStatus(dropOff.id(), body("status", "PICKED_UP", "feeCollected", true), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").value("The delivery fee isn't collected at this step."));
        postStatus(pickupFee.id(), body("status", "DELIVERED", "recipientName", "Nimal Perera",
                "proofPhotoId", PROOF_ID, "feeCollected", true), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").value("The delivery fee isn't collected at this step."));
        // Without the flag both steps go through: nothing is collected there.
        postStatus(dropOff.id(), body("status", "PICKED_UP"), courier.cookie()).andExpect(status().isOk());
        verify(delivery, never()).recordFeeCollection(any());
    }

    // ---- Online and older parcels ----

    @Test
    void onlineAndOlderParcelsAreNeverBlockedAndNeverCollectedInCash() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot online = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.ONLINE);
        DeliverySnapshot legacy = given(parcel(courier.id(), ParcelStatus.CREATED));

        postStatus(online.id(), body("status", "PICKED_UP", "feeCollected", true), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected")
                        .value("This delivery fee is paid online: there is no cash to collect."));
        postStatus(legacy.id(), body("status", "PICKED_UP", "feeCollected", true), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected")
                        .value("No payment method is recorded for this parcel: there is no fee to collect."));

        postStatus(online.id(), body("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isOk())
                // choosing ONLINE is never a payment
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_ONLINE_VERIFICATION"));
        postStatus(legacy.id(), body("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("NOT_RECORDED"));
        verify(delivery, never()).recordFeeCollection(any());
    }

    // ---- Duplicates and ownership ----

    @Test
    void anAlreadyPaidFeeIsNotCollectedAgain() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = withFee(parcel(courier.id(), ParcelStatus.CREATED), FEE, "LKR", null,
                PaymentMethod.AT_PICKUP);
        given(withPayment(parcel, cash(parcel)));

        postStatus(parcel.id(), body("status", "PICKED_UP", "feeCollected", true), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_ALREADY_COLLECTED"));
        verify(delivery, never()).changeStatus(any());

        // Without the flag the pickup goes through: the fee is already paid.
        postStatus(parcel.id(), body("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"));
        verify(delivery, never()).recordFeeCollection(any());
    }

    @Test
    void anotherCouriersParcelAnswers404AndCollectsNothing() throws Exception {
        Courier owner = registerCourier();
        Courier other = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = booked(owner.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_PICKUP);

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(other.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.deliveryFee").doesNotExist());
        postStatus(parcel.id(), body("status", "PICKED_UP", "feeCollected", true), other.cookie())
                .andExpect(status().isNotFound());
        verify(delivery, never()).recordFeeCollection(any());
    }

    @Test
    void aStepOfAClosedTaskCollectsNothing() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot parcel = booked(courier.id(), ParcelStatus.CREATED, null, PaymentMethod.AT_PICKUP);
        var task = taskOf(parcel.id());
        replaceTask(new com.swiftdrop.dispatch.AssignmentView(task.id(), task.courierId(), task.type(),
                com.swiftdrop.dispatch.AssignmentStatus.CANCELLED, task.originBranch(), task.destinationBranch(),
                task.scheduledFor(), task.note(), task.assignedAt(), task.startedAt(), null, Instant.now(), task.items(),
                task.version() + 1));

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
        postStatus(parcel.id(), body("status", "PICKED_UP", "feeCollected", true), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        verify(delivery, never()).recordFeeCollection(any());
    }
}
