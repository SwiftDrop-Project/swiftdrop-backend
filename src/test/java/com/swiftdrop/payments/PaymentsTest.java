package com.swiftdrop.payments;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.RecordFeeCollectionCommand;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /payments against the real database (Testcontainers): one record per delivery, the statuses each method gets, the
 * totals, and paying an online delivery with a mock card.
 */
class PaymentsTest extends IntegrationTest {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String NEXT_YEAR = YearMonth.now(ZoneId.of("Asia/Colombo")).plusYears(1)
            .format(DateTimeFormatter.ofPattern("MM/yy"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DeliveryApi deliveries;

    // ---- 401 / 403 ----

    @Test
    void withoutLoginItIs401AndForAnotherRoleItIs403() throws Exception {
        mvc.perform(get(PAYMENTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(PAYMENTS + "/summary")).andExpect(status().isUnauthorized());
        postJson(PAYMENTS + "/" + UUID.randomUUID() + "/card", card("4242 4242 4242 4242"))
                .andExpect(status().isUnauthorized());

        Cookie admin = loginAsAdmin();
        mvc.perform(get(PAYMENTS).cookie(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(PAYMENTS + "/summary").cookie(admin)).andExpect(status().isForbidden());
    }

    // ---- one record per delivery ----

    @Test
    void everyDeliveryHasOnePaymentThatIsDueAtFirst() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String online = book(customer, "ONLINE");
        book(customer, "AT_DROP_OFF");
        book(registerCustomer(uniqueEmail(), uniquePhone()), "ONLINE");

        mvc.perform(get(PAYMENTS).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("DUE"))))
                .andExpect(jsonPath("$.content[1].parcelId").value(online))
                .andExpect(jsonPath("$.content[1].method").value("ONLINE"))
                .andExpect(jsonPath("$.content[1].amount").value(notNullValue()))
                .andExpect(jsonPath("$.content[1].paidAt").value(nullValue()));
        // asking again doesn't make a second record
        mvc.perform(get(PAYMENTS).cookie(customer)).andExpect(jsonPath("$.totalElements").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE parcel_id = ?", Long.class,
                UUID.fromString(online))).isEqualTo(1);
    }

    @Test
    void cashIsPaidOnceACourierRecordedTheCollection() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String atPickup = book(customer, "AT_PICKUP");
        String delivered = book(customer, "AT_DROP_OFF");
        String deliveredNotRecorded = book(customer, "AT_DROP_OFF");
        String failed = book(customer, "AT_DROP_OFF");
        parcelStatus(atPickup, "PICKED_UP");
        parcelStatus(delivered, "DELIVERED");
        parcelStatus(deliveredNotRecorded, "DELIVERED");
        parcelStatus(failed, "FAILED");
        // WP9's courier screens record the cash; here straight through delivery's contract.
        collectCash(atPickup);
        collectCash(delivered);

        mvc.perform(get(PAYMENTS).cookie(customer))
                .andExpect(jsonPath("$.content[?(@.parcelId == '" + atPickup + "')].status").value("PAID"))
                .andExpect(jsonPath("$.content[?(@.parcelId == '" + delivered + "')].status").value("PAID"))
                .andExpect(jsonPath("$.content[?(@.parcelId == '" + deliveredNotRecorded + "')].status")
                        .value("DUE"))
                .andExpect(jsonPath("$.content[?(@.parcelId == '" + failed + "')].status").value("NOT_CHARGED"));
        mvc.perform(get(PAYMENTS + "/summary").cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(2))
                .andExpect(jsonPath("$.dueCount").value(1))
                .andExpect(jsonPath("$.notChargedCount").value(1));
        // The receipt reads the same rule.
        mvc.perform(get("/api/v1/deliveries/{id}/receipt", delivered).cookie(customer))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        mvc.perform(get("/api/v1/deliveries/{id}/receipt", deliveredNotRecorded).cookie(customer))
                .andExpect(jsonPath("$.paymentStatus").value("DUE"));
    }

    @Test
    void theSummarySplitsWhatIsDueIntoCashAndOnline() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        book(customer, "ONLINE");
        String cash = book(customer, "AT_DROP_OFF");

        mvc.perform(get(PAYMENTS + "/summary").cookie(customer))
                .andExpect(jsonPath("$.dueCount").value(2))
                .andExpect(jsonPath("$.dueCashCount").value(1))
                .andExpect(jsonPath("$.dueOnlineCount").value(1))
                .andExpect(jsonPath("$.paidCount").value(0))
                .andExpect(jsonPath("$.dueCashTrackingNumber").value(trackingNumber(cash)));
    }

    // ---- mock card ----

    @Test
    void anOnlinePaymentIsPaidWithATestCardKeepingOnlyTheLastFourDigits() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String parcelId = book(customer, "ONLINE");
        String id = onlyPaymentId(customer);
        mvc.perform(get("/api/v1/deliveries/{id}/receipt", parcelId).cookie(customer))
                .andExpect(jsonPath("$.paymentStatus").value("DUE"));

        postJson(PAYMENTS + "/" + id + "/card", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.cardBrand").value("VISA"))
                .andExpect(jsonPath("$.cardLast4").value("4242"))
                .andExpect(jsonPath("$.paidAt").value(notNullValue()));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM payments.payments WHERE id = ?", UUID.fromString(id));
        assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains("42424242"));
        // One rule: delivery recorded the verified online payment, so the receipt says paid too.
        assertThat(jdbc.queryForObject("SELECT external_reference FROM delivery.fee_payments WHERE parcel_id = ?",
                String.class, UUID.fromString(parcelId))).isEqualTo("payments:" + id);
        assertThat(deliveries.findById(UUID.fromString(parcelId)).orElseThrow().feePaymentStatus())
                .isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
        mvc.perform(get("/api/v1/deliveries/{id}/receipt", parcelId).cookie(customer))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        // paying twice is refused
        postJson(PAYMENTS + "/" + id + "/card", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"));
    }

    @Test
    void theDeclineTestCardIsDeclinedAndNothingChanges() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        book(customer, "ONLINE");
        String id = onlyPaymentId(customer);

        postJson(PAYMENTS + "/" + id + "/card", card("4000 0000 0000 0002"), customer)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("CARD_DECLINED"));
        mvc.perform(get(PAYMENTS).cookie(customer)).andExpect(jsonPath("$.content[0].status").value("DUE"));
    }

    @Test
    void cardDetailsAreValidatedAndOnlyTestCardsAreAccepted() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        book(customer, "ONLINE");
        String id = onlyPaymentId(customer);

        postJson(PAYMENTS + "/" + id + "/card", Map.of("cardNumber", "abc", "expiry", "13/28", "cvc", "1",
                "nameOnCard", ""), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cardNumber").value("Enter the card number."))
                .andExpect(jsonPath("$.fieldErrors.expiry").value("Enter the expiry date as MM/YY."))
                .andExpect(jsonPath("$.fieldErrors.cvc").value("Enter the 3 or 4 digit security code."))
                .andExpect(jsonPath("$.fieldErrors.nameOnCard").value("Enter the name on the card."));
        postJson(PAYMENTS + "/" + id + "/card", card("4111 1111 1111 1111"), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cardNumber").value("Use a test card, like 4242 4242 4242 4242."));
    }

    @Test
    void cashPaymentsCantBePaidByCard() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        book(customer, "AT_PICKUP");

        postJson(PAYMENTS + "/" + onlyPaymentId(customer) + "/card", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"));
    }

    @Test
    void someoneElsesPaymentAndAnUnknownIdAreNotFound() throws Exception {
        Cookie owner = registerCustomer(uniqueEmail(), uniquePhone());
        book(owner, "ONLINE");
        String theirs = onlyPaymentId(owner);
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        for (String id : new String[] {theirs, UUID.randomUUID().toString()}) {
            postJson(PAYMENTS + "/" + id + "/card", card("4242 4242 4242 4242"), customer)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(get(PAYMENTS).cookie(owner)).andExpect(jsonPath("$.content[0].status").value("DUE"));
    }

    @Test
    void aBadPageOrSizeIsRefused() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        mvc.perform(get(PAYMENTS).cookie(customer).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.page").value("Use a page number of 0 or more."));
        mvc.perform(get(PAYMENTS).cookie(customer).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").value("Use a page size from 1 to 100."));
        mvc.perform(get(PAYMENTS).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // ---- Create delivery with an online payment ----

    @Test
    void theGatewayCheckApprovesOnlyTestCardsAndChargesNothing() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        postJson(PAYMENTS + "/card-check", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardBrand").value("VISA"))
                .andExpect(jsonPath("$.cardLast4").value("4242"));
        postJson(PAYMENTS + "/card-check", card("4000 0000 0000 0002"), customer)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("CARD_DECLINED"));
        postJson(PAYMENTS + "/card-check", card("4111 1111 1111 1111"), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cardNumber").value("Use a test card, like 4242 4242 4242 4242."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payments", Long.class)).isZero();
    }

    @Test
    void theGatewayCheckNeedsACustomer() throws Exception {
        postJson(PAYMENTS + "/card-check", card("4242 4242 4242 4242")).andExpect(status().isUnauthorized());
        postJson(PAYMENTS + "/card-check", card("4242 4242 4242 4242"), loginAsAdmin())
                .andExpect(status().isForbidden());
    }

    @Test
    void aJustBookedOnlineDeliveryIsPaidByItsIdAndIsPaidEverywhere() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String parcelId = book(customer, "ONLINE");

        // Straight after booking, before the Payments page ever listed it.
        postJson(PAYMENTS + "/parcels/" + parcelId + "/card", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(parcelId))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.cardLast4").value("4242"));

        assertThat(deliveries.findById(UUID.fromString(parcelId)).orElseThrow().feePaymentStatus())
                .isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
        mvc.perform(get(PAYMENTS).cookie(customer))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PAID"));
        postJson(PAYMENTS + "/parcels/" + parcelId + "/card", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"));
    }

    @Test
    void anotherCustomersDeliveryCantBePaidByItsId() throws Exception {
        String theirs = book(registerCustomer(uniqueEmail(), uniquePhone()), "ONLINE");
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        postJson(PAYMENTS + "/parcels/" + theirs + "/card", card("4242 4242 4242 4242"), customer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(deliveries.findById(UUID.fromString(theirs)).orElseThrow().feePaymentStatus())
                .isEqualTo(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION);
    }

    // ---- helpers ----

    private static Map<String, Object> card(String number) {
        Map<String, Object> body = new HashMap<>();
        body.put("cardNumber", number);
        body.put("expiry", NEXT_YEAR);
        body.put("cvc", "123");
        body.put("nameOnCard", "Nimali Perera");
        return body;
    }

    /** Books a delivery and returns its parcel id. */
    private String book(Cookie customer, String paymentMethod) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", Map.of("name", "Nimal Perera", "phone", uniquePhone(), "addressLine", "12 Galle Road",
                "city", "Colombo 03", "district", "COLOMBO"));
        body.put("recipient", Map.of("name", "Kamala Silva", "phone", uniquePhone(),
                "addressLine", "45 Peradeniya Road", "city", "Kandy", "district", "KANDY"));
        body.put("weightKg", new BigDecimal("1.50"));
        body.put("lengthCm", 30);
        body.put("widthCm", 20);
        body.put("heightCm", 10);
        body.put("paymentMethod", paymentMethod);
        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }

    private String onlyPaymentId(Cookie customer) throws Exception {
        String response = mvc.perform(get(PAYMENTS).cookie(customer))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("content").get(0).get("id").asString();
    }

    private void parcelStatus(String parcelId, String status) {
        jdbc.update("UPDATE delivery.parcels SET status = ? WHERE id = ?", status, UUID.fromString(parcelId));
    }

    /** A courier collected the cash fee (delivery checks the parcel is at the matching status). */
    private void collectCash(String parcelId) {
        deliveries.recordFeeCollection(
                new RecordFeeCollectionCommand(UUID.fromString(parcelId), UUID.randomUUID(), UUID.randomUUID()));
    }

    private String trackingNumber(String parcelId) {
        return jdbc.queryForObject("SELECT tracking_number FROM delivery.parcels WHERE id = ?", String.class,
                UUID.fromString(parcelId));
    }
}
