package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.RecordFeeCollectionCommand;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
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
 * Delivery fee collection with the REAL delivery (WP3) and dispatch (WP8) modules on PostgreSQL (Testcontainers):
 * the payment row in delivery.fee_payments, its amount taken from the parcel, one row per parcel even under a race, and
 * the payment kept through handovers, transfers, reassignment and failed deliveries.
 */
class CourierFeeCollectionTest extends RealWorkflowTest {

    private static final BigDecimal COD = new BigDecimal("2500.00");

    private Map<String, Object> paymentRow(UUID parcelId) {
        return jdbc.queryForMap("select amount, currency, payment_method, source, collected_by, assignment_id,"
                + " external_reference, collected_at from delivery.fee_payments where parcel_id = ?", parcelId);
    }

    /** DELIVERED with a fresh proof photo, confirming the delivery fee was collected from the recipient. */
    private ResultActions deliverWithFee(StaffCourier courier, UUID parcelId) throws Exception {
        UUID photo = uploadProof(courier, parcelId);
        return changeStatus(courier, parcelId, Map.of("status", "DELIVERED", "recipientName", "Kamala Silva",
                "proofPhotoId", photo.toString(), "feeCollected", true));
    }

    @Test
    void anAtPickupFeeIsCollectedOnceAtPickupAndStaysCollectedToTheEnd() throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier transferCourier = createCourier("GAMPAHA");
        StaffCourier deliveryCourier = createCourier("MATARA");
        UUID parcel = bookParcel("AT_PICKUP", COD);
        UUID pickup = pickupTask(pickupCourier, "GAMPAHA", parcel);
        BigDecimal fee = parcel(parcel).feeAmount();

        assertThat(todayCard(pickupCourier, parcel).get("deliveryFee").get("collectOn").asString()).isEqualTo("PICKED_UP");

        // Without the confirmation nothing happens.
        changeStatus(pickupCourier, parcel, Map.of("status", "PICKED_UP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").exists());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(feePaymentRows(parcel)).isZero();

        moveTo(pickupCourier, parcel, "PICKED_UP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());

        Map<String, Object> row = paymentRow(parcel);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(fee);
        assertThat(row.get("currency")).isEqualTo("LKR");
        assertThat(row.get("payment_method")).isEqualTo("AT_PICKUP");
        assertThat(row.get("source")).isEqualTo("COURIER_CASH");
        assertThat(row.get("collected_by")).isEqualTo(pickupCourier.id());
        assertThat(row.get("assignment_id")).isEqualTo(pickup);
        assertThat(row.get("external_reference")).isNull();
        DeliverySnapshot afterPickup = parcel(parcel);
        assertThat(afterPickup.feePaymentStatus()).isEqualTo(FeePaymentStatus.CASH_COLLECTED);
        assertThat(afterPickup.feePayment().collectedBy()).isEqualTo(pickupCourier.id());

        // Handover, transfer and a delivery by two other couriers: the payment stays, nothing more is collected.
        handOver(pickupCourier, parcel, "S. Silva").andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"));
        transferTask(transferCourier, "GAMPAHA", "MATARA", parcel);
        moveTo(transferCourier, parcel, "IN_TRANSIT").andExpect(status().isOk());
        handOver(transferCourier, parcel, "R. Perera").andExpect(status().isOk());
        deliveryTask(deliveryCourier, "MATARA", parcel);
        moveTo(deliveryCourier, parcel, "OUT_FOR_DELIVERY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty())
                // the cash on delivery is still collected at delivery, apart from the fee
                .andExpect(jsonPath("$.codAmount").value(2500.00));
        // A second "collection" at delivery is refused...
        deliverWithFee(deliveryCourier, parcel)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_ALREADY_COLLECTED"));
        // ...and the plain delivery goes through.
        deliver(deliveryCourier, parcel, "Kamala Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"));

        assertThat(feePaymentRows(parcel)).isEqualTo(1);
        assertThat(paymentRow(parcel).get("collected_by")).isEqualTo(pickupCourier.id());
        // The pickup courier's history entry shows the payment, read-only.
        JsonNode entry = read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(COURIER + "/{p}/history/{a}", parcel, pickup).cookie(pickupCourier.cookie())));
        assertThat(entry.get("deliveryFee").get("status").asString()).isEqualTo("CASH_COLLECTED");
        assertThat(entry.get("deliveryFee").get("collectOn").isNull()).isTrue();
    }

    @Test
    void anAtDropOffFeeSurvivesAFailedDeliveryAndIsCollectedByTheNextCourier() throws Exception {
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier firstDelivery = createCourier("GAMPAHA");
        StaffCourier secondDelivery = createCourier("GAMPAHA");
        // The recipient is in Gampaha, so the parcel is delivered from the Gampaha branch.
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA", "AT_DROP_OFF", null);
        pickupTask(pickupCourier, "GAMPAHA", parcel);

        // Not collected at pickup.
        assertThat(todayCard(pickupCourier, parcel).get("deliveryFee").get("collectOn").isNull()).isTrue();
        changeStatus(pickupCourier, parcel, Map.of("status", "PICKED_UP", "feeCollected", true))
                .andExpect(status().isBadRequest());
        changeStatus(pickupCourier, parcel, Map.of("status", "PICKED_UP")).andExpect(status().isOk());
        handOver(pickupCourier, parcel, "S. Silva").andExpect(status().isOk());

        // First delivery: the recipient refuses, the parcel goes back. No payment.
        deliveryTask(firstDelivery, "GAMPAHA", parcel);
        moveTo(firstDelivery, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.collectOn").value("DELIVERED"));
        changeStatus(firstDelivery, parcel, Map.of("status", "FAILED", "failureReason", "RECIPIENT_REFUSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_CASH_COLLECTION"))
                .andExpect(jsonPath("$.deliveryFee.collectOn").isEmpty());
        handOver(firstDelivery, parcel, "S. Silva").andExpect(status().isOk());
        assertThat(feePaymentRows(parcel)).isZero();

        // Second delivery, another courier: still awaiting, collected now, by them, in their task.
        UUID second = deliveryTask(secondDelivery, "GAMPAHA", parcel);
        moveTo(secondDelivery, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(secondDelivery, parcel, "Kamala Silva")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.feeCollected").exists());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        deliverWithFee(secondDelivery, parcel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.proof.hasPhoto").value(true))
                .andExpect(jsonPath("$.deliveryFee.status").value("CASH_COLLECTED"));

        Map<String, Object> row = paymentRow(parcel);
        assertThat(row.get("payment_method")).isEqualTo("AT_DROP_OFF");
        assertThat(row.get("collected_by")).isEqualTo(secondDelivery.id());
        assertThat(row.get("assignment_id")).isEqualTo(second);
    }

    @Test
    void aReassignedPickupIsCollectedByTheCourierWhoPicksItUp() throws Exception {
        StaffCourier first = createCourier("GAMPAHA");
        StaffCourier second = createCourier("GAMPAHA");
        UUID parcel = bookParcel("AT_PICKUP", null);
        UUID task = pickupTask(first, "GAMPAHA", parcel);
        postJson(DISPATCH_TASKS + "/" + task + "/reassign", Map.of("courierId", second.id().toString()), dispatcher)
                .andExpect(status().isOk());

        changeStatus(first, parcel, Map.of("status", "PICKED_UP", "feeCollected", true))
                .andExpect(status().isNotFound());
        assertThat(feePaymentRows(parcel)).isZero();

        moveTo(second, parcel, "PICKED_UP").andExpect(status().isOk());
        assertThat(paymentRow(parcel).get("collected_by")).isEqualTo(second.id());
        assertThat(paymentRow(parcel).get("assignment_id")).isEqualTo(task);
    }

    @Test
    void twoConfirmationsAtTheSameMomentKeepOnePayment() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("AT_PICKUP", null);
        pickupTask(courier, "GAMPAHA", parcel);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> confirm = () -> {
                start.await();
                MvcResult result = changeStatus(courier, parcel, Map.of("status", "PICKED_UP", "feeCollected", true))
                        .andReturn();
                return result.getResponse().getStatus();
            };
            List<Future<Integer>> results = new ArrayList<>(List.of(pool.submit(confirm), pool.submit(confirm)));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            // One wins; the other is refused (a 409: the parcel or task changed, or the fee was just paid).
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(feePaymentRows(parcel)).isEqualTo(1);
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(historyRows(parcel)).isEqualTo(2);
    }

    @Test
    void deliveryRecordsOnlyACashFeeAtItsOwnStatusAndOnlyOnce() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID atPickup = bookParcel("AT_PICKUP", null);
        UUID online = bookParcel("ONLINE", null);
        UUID task = pickupTask(courier, "GAMPAHA", atPickup, online);

        // Not picked up yet: refused, nothing written.
        assertThatThrownBy(() -> deliveries.recordFeeCollection(new RecordFeeCollectionCommand(atPickup, courier.id(),
                task)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FEE_COLLECTION_NOT_ALLOWED));
        // ONLINE is never collected in cash.
        changeStatus(courier, online, Map.of("status", "PICKED_UP")).andExpect(status().isOk());
        assertThatThrownBy(() -> deliveries.recordFeeCollection(new RecordFeeCollectionCommand(online, courier.id(),
                task)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FEE_COLLECTION_NOT_ALLOWED));
        assertThat(parcel(online).feePaymentStatus()).isEqualTo(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION);

        moveTo(courier, atPickup, "PICKED_UP").andExpect(status().isOk());
        assertThatThrownBy(() -> deliveries.recordFeeCollection(new RecordFeeCollectionCommand(atPickup, courier.id(),
                task)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FEE_ALREADY_COLLECTED));
        assertThat(feePaymentRows(atPickup)).isEqualTo(1);
        assertThat(feePaymentRows(online)).isZero();
    }

    @Test
    void theDatabaseKeepsOnePaymentPerParcelAndOnlyConsistentRows() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("AT_PICKUP", null);
        UUID task = pickupTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());

        String insert = "insert into delivery.fee_payments (id, parcel_id, amount, currency, payment_method, source,"
                + " collected_by, assignment_id, external_reference, collected_at)"
                + " values (?, ?, 1.00, 'LKR', ?, ?, ?, ?, ?, now())";
        // A second payment for the same parcel.
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), parcel, "AT_PICKUP", "COURIER_CASH",
                courier.id(), task, null)).isInstanceOf(DataIntegrityViolationException.class);
        UUID other = UUID.randomUUID();
        // Cash for an online fee, cash without a courier, online without a provider reference.
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), other, "ONLINE", "COURIER_CASH", courier.id(),
                task, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), other, "AT_PICKUP", "COURIER_CASH", null,
                null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), other, "ONLINE", "ONLINE", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(feePaymentRows(parcel)).isEqualTo(1);
    }

    @Test
    void onlineStaysUnconfirmedUntilAVerifiedPaymentIsRecorded() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("ONLINE", null);
        pickupTask(courier, "GAMPAHA", parcel);

        JsonNode card = todayCard(courier, parcel).get("deliveryFee");
        assertThat(card.get("status").asString()).isEqualTo("AWAITING_ONLINE_VERIFICATION");
        assertThat(card.get("collectOn").isNull()).isTrue();
        // Never blocked, never paid by the courier's step.
        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isBadRequest());
        changeStatus(courier, parcel, Map.of("status", "PICKED_UP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_ONLINE_VERIFICATION"));
        assertThat(feePaymentRows(parcel)).isZero();

        // A verified online payment, as the payments module (WP2) records it after verifying it.
        deliveries.recordOnlinePayment(new com.swiftdrop.delivery.RecordOnlinePaymentCommand(parcel,
                "payments:test-provider-ref"));
        DeliverySnapshot verified = parcel(parcel);
        assertThat(verified.feePaymentStatus()).isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
        assertThat(verified.feePayment().source()).isEqualTo(FeePaymentSource.ONLINE);
        detail(courier, parcel).andExpect(jsonPath("$.deliveryFee.status").value("ONLINE_VERIFIED"));
    }

    @Test
    void aParcelBookedBeforePaymentMethodsIsNeverBlocked() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("AT_PICKUP", null);
        // As a parcel booked before payment methods (the column is nullable for them).
        jdbc.update("update delivery.parcels set payment_method = null where id = ?", parcel);
        pickupTask(courier, "GAMPAHA", parcel);

        assertThat(todayCard(courier, parcel).get("deliveryFee").get("status").asString()).isEqualTo("NOT_RECORDED");
        changeStatus(courier, parcel, Map.of("status", "PICKED_UP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("NOT_RECORDED"));
        assertThat(feePaymentRows(parcel)).isZero();
    }

    @Test
    void aSenderWhoDoesNotPayIsUnableToCollectAndPaysNothing() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("AT_PICKUP", null);
        UUID task = pickupTask(courier, "GAMPAHA", parcel);

        unableToCollect(courier, parcel, "SENDER_UNAVAILABLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFee.status").value("AWAITING_CASH_COLLECTION"));
        assertThat(itemOutcomeRow(task, parcel)).isEqualTo("NOT_COLLECTED");
        assertThat(feePaymentRows(parcel)).isZero();
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.CREATED);
    }
}
