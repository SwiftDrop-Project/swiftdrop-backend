package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.RecordOnlinePaymentCommand;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verified online payments ({@code DeliveryApi.recordOnlinePayment}, for the payments module, WP2) with the REAL
 * delivery and dispatch modules on PostgreSQL (Testcontainers): the payment row in delivery.fee_payments, ONLINE_VERIFIED
 * on the courier API, only for ONLINE parcels, once per parcel even under a race, and nothing kept when the caller's
 * transaction rolls back. The WP2 call site itself is not part of this branch; these tests call the contract directly.
 */
class CourierOnlinePaymentTest extends RealWorkflowTest {

    private static final String REFERENCE = "payments:3f1d2c4b-0b6c-4a51-9d2e-3a4f5b6c7d8e";

    @Autowired
    PlatformTransactionManager transactions;

    private static ErrorCode codeOf(Throwable thrown) {
        return ((ApiException) thrown).code();
    }

    @Test
    void aVerifiedOnlinePaymentIsRecordedWithTheParcelsFeeAndShownToTheCourierAsPaid() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("ONLINE", new BigDecimal("1200.00"));
        pickupTask(courier, "GAMPAHA", parcel);
        BigDecimal fee = parcel(parcel).feeAmount();

        // Choosing ONLINE at booking is not a payment.
        assertThat(parcel(parcel).feePaymentStatus()).isEqualTo(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION);
        assertThat(todayCard(courier, parcel).get("deliveryFee").get("status").asString())
                .isEqualTo("AWAITING_ONLINE_VERIFICATION");

        DeliverySnapshot paid = deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, REFERENCE));

        assertThat(paid.feePaymentStatus()).isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
        assertThat(paid.feePayment().source()).isEqualTo(FeePaymentSource.ONLINE);
        assertThat(paid.feePayment().amount()).isEqualByComparingTo(fee);
        assertThat(paid.feePayment().externalReference()).isEqualTo(REFERENCE);
        assertThat(paid.feePayment().collectedBy()).isNull();
        assertThat(paid.feePayment().assignmentId()).isNull();
        Map<String, Object> row = jdbc.queryForMap("select amount, currency, payment_method, source, collected_by,"
                + " assignment_id, external_reference from delivery.fee_payments where parcel_id = ?", parcel);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(fee);
        assertThat(row.get("currency")).isEqualTo("LKR");
        assertThat(row.get("payment_method")).isEqualTo("ONLINE");
        assertThat(row.get("source")).isEqualTo("ONLINE");
        assertThat(row.get("collected_by")).isNull();
        assertThat(row.get("assignment_id")).isNull();
        assertThat(row.get("external_reference")).isEqualTo(REFERENCE);

        // The courier API: today and the detail say ONLINE_VERIFIED, with nothing to collect.
        JsonNode fee1 = todayCard(courier, parcel).get("deliveryFee");
        assertThat(fee1.get("status").asString()).isEqualTo("ONLINE_VERIFIED");
        assertThat(fee1.get("collectedAt").isNull()).isFalse();
        assertThat(fee1.get("collectOn").isNull()).isTrue();
        detail(courier, parcel)
                .andExpect(jsonPath("$.deliveryFee.status").value("ONLINE_VERIFIED"))
                .andExpect(jsonPath("$.deliveryFee.paymentMethod").value("ONLINE"))
                // the cash on delivery is unaffected
                .andExpect(jsonPath("$.codAmount").value(1200.00));
        // A courier can't "collect" it again; the plain pickup works and keeps the payment.
        changeStatus(courier, parcel, Map.of("status", "PICKED_UP", "feeCollected", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_ALREADY_COLLECTED"));
        changeStatus(courier, parcel, Map.of("status", "PICKED_UP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("ONLINE_VERIFIED"));
        assertThat(feePaymentRows(parcel)).isEqualTo(1);
    }

    @Test
    void cashAndLegacyParcelsAndUnknownParcelsAreRefusedAndNothingIsWritten() throws Exception {
        UUID atPickup = bookParcel("AT_PICKUP", null);
        UUID atDropOff = bookParcel("AT_DROP_OFF", null);
        UUID legacy = bookParcel("ONLINE", null);
        jdbc.update("update delivery.parcels set payment_method = null where id = ?", legacy);

        for (UUID parcel : List.of(atPickup, atDropOff, legacy)) {
            assertThatThrownBy(() -> deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, REFERENCE)))
                    .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.FEE_COLLECTION_NOT_ALLOWED));
            assertThat(feePaymentRows(parcel)).isZero();
        }
        assertThat(parcel(legacy).feePaymentStatus()).isEqualTo(FeePaymentStatus.NOT_RECORDED);
        assertThat(parcel(atPickup).feePaymentStatus()).isEqualTo(FeePaymentStatus.AWAITING_CASH_COLLECTION);
        assertThatThrownBy(() -> deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(UUID.randomUUID(),
                REFERENCE)))
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void aSecondPaymentIsRefusedWhetherOnlineOrCash() throws Exception {
        UUID parcel = bookParcel("ONLINE", null);
        deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, REFERENCE));

        assertThatThrownBy(() -> deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel,
                "payments:another-attempt")))
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.FEE_ALREADY_COLLECTED));
        assertThat(feePaymentRows(parcel)).isEqualTo(1);
        assertThat(parcel(parcel).feePayment().externalReference()).isEqualTo(REFERENCE);
    }

    /**
     * A real race: the first payment's transaction stays open while the second starts, so the second can't see it
     * yet and its insert waits on uk_fee_payments_parcel_id until the first commits, then fails. One payment kept.
     */
    @Test
    void twoPaymentsAtTheSameMomentKeepOne() throws Exception {
        UUID parcel = bookParcel("ONLINE", null);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        CountDownLatch firstInserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = () -> transaction.execute(status -> {
                deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, "payments:first"));
                firstInserted.countDown();
                sleep(700); // keep the transaction open while the second one tries
                return "ok";
            });
            Callable<String> second = () -> {
                firstInserted.await(30, TimeUnit.SECONDS);
                try {
                    transaction.execute(status -> deliveries.recordOnlinePayment(
                            new RecordOnlinePaymentCommand(parcel, "payments:second")));
                    return "ok";
                } catch (ApiException ex) {
                    return ex.code().name();
                }
            };
            List<Future<String>> results = new ArrayList<>(List.of(pool.submit(first), pool.submit(second)));
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactly("ok", "FEE_ALREADY_COLLECTED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(feePaymentRows(parcel)).isEqualTo(1);
        assertThat(parcel(parcel).feePayment().externalReference()).isEqualTo("payments:first");
    }

    @Test
    void itJoinsTheCallersTransactionAndRollsBackWithIt() throws Exception {
        UUID parcel = bookParcel("ONLINE", null);
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        // As WP2's payByCard would: the payment, then the caller's own write fails.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            DeliverySnapshot inside = deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, REFERENCE));
            assertThat(inside.feePaymentStatus()).isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
            // visible inside the same transaction
            assertThat(deliveries.findById(parcel).orElseThrow().feePaymentStatus())
                    .isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
            throw new IllegalStateException("simulated failure of the caller's own payment record");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(feePaymentRows(parcel)).isZero();
        assertThat(parcel(parcel).feePaymentStatus()).isEqualTo(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION);
        // Nothing half done: paying again works.
        deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, REFERENCE));
        assertThat(parcel(parcel).feePaymentStatus()).isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
    }

    @Test
    void anOnlinePaymentSurvivesHandoverAndAFailedDeliveryAndNeverAsksForCash() throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier deliveryCourier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA", "ONLINE", null);
        deliveries.recordOnlinePayment(new RecordOnlinePaymentCommand(parcel, REFERENCE));

        pickupTask(pickupCourier, "GAMPAHA", parcel);
        changeStatus(pickupCourier, parcel, Map.of("status", "PICKED_UP")).andExpect(status().isOk());
        handOver(pickupCourier, parcel, "S. Silva").andExpect(status().isOk());
        deliveryTask(deliveryCourier, "GAMPAHA", parcel);
        moveTo(deliveryCourier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("ONLINE_VERIFIED"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
        changeStatus(deliveryCourier, parcel, Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("ONLINE_VERIFIED"));
        assertThat(feePaymentRows(parcel)).isEqualTo(1);
    }

    @Test
    void anUnconfirmedOnlinePaymentStaysUnpaidThroughTheWholeJourney() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA", "ONLINE", null);
        pickupTask(courier, "GAMPAHA", parcel);
        changeStatus(courier, parcel, Map.of("status", "PICKED_UP"))
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_ONLINE_VERIFICATION"));
        handOver(courier, parcel, "S. Silva").andExpect(status().isOk());
        deliveryTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(courier, parcel, "Kamala Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_ONLINE_VERIFICATION"));
        assertThat(feePaymentRows(parcel)).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
