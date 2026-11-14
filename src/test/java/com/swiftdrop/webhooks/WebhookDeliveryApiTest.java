package com.swiftdrop.webhooks;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test ping, delivery history, retry and the admin endpoints (API.md "Webhooks"). Nothing is sent here (the
 * dispatcher is off in tests; WebhookDispatcherTest covers sending). Deliveries in other states are made with the
 * repository and plain SQL.
 */
class WebhookDeliveryApiTest extends IntegrationTest {

    private static final String PATH = "/api/v1/merchant/webhooks";
    private static final String ADMIN_PATH = "/api/v1/admin/webhooks/deliveries";
    private static final String URL = "https://shop.example.com/swiftdrop";

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Autowired
    JdbcTemplate jdbc;

    // ---------- authorization ----------

    @Test
    void withoutLoginEveryEndpointAnswers401() throws Exception {
        UUID id = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(post(PATH + "/" + id + "/test"),
                get(PATH + "/" + id + "/deliveries"), post(PATH + "/deliveries/" + id + "/retry"), get(ADMIN_PATH),
                post(ADMIN_PATH + "/" + id + "/retry"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void customersCantUseTheMerchantEndpoints() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        UUID id = UUID.randomUUID();

        for (MockHttpServletRequestBuilder request : List.of(post(PATH + "/" + id + "/test"),
                get(PATH + "/" + id + "/deliveries"), post(PATH + "/deliveries/" + id + "/retry"))) {
            mvc.perform(request.cookie(customer)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void onlyAdminsCanUseTheAdminEndpoints() throws Exception {
        Cookie merchant = approvedMerchant();

        mvc.perform(get(ADMIN_PATH).cookie(merchant)).andExpect(status().isForbidden());
        mvc.perform(post(ADMIN_PATH + "/" + UUID.randomUUID() + "/retry").cookie(merchant))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKeyRequestsGet403() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        String rawKey = json.readTree(postJson("/api/v1/merchant/api-keys", Map.of("name", "shop"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("rawKey").asString();

        mvc.perform(post(PATH + "/" + subscription + "/test").header("X-Api-Key", rawKey))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/" + subscription + "/deliveries").header("X-Api-Key", rawKey))
                .andExpect(status().isForbidden());
    }

    // ---------- test ping ----------

    @Test
    void aTestPingIsQueuedAndAnswers202() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);

        JsonNode ping = readJson(mvc.perform(post(PATH + "/" + subscription + "/test").cookie(merchant))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.subscriptionId").value(subscription))
                .andExpect(jsonPath("$.eventType").value("webhook.ping"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.nextAttemptAt").isNotEmpty())
                .andExpect(jsonPath("$.lastError").isEmpty())
                .andExpect(jsonPath("$.payload").doesNotExist()));

        String payload = jdbc.queryForObject("select payload from webhooks.deliveries where id = ?::uuid",
                String.class, ping.get("id").asString());
        assertThat(payload).startsWith("{\"id\":\"" + ping.get("eventId").asString() + "\",\"type\":\"webhook.ping\"")
                .endsWith("\"data\":{\"subscriptionId\":\"" + subscription + "\"}}");
    }

    @Test
    void aSecondPingWhileOneIsWaitingReturnsTheSameOne() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);

        String first = readJson(ping(merchant, subscription).andExpect(status().isAccepted())).get("id").asString();
        String second = readJson(ping(merchant, subscription).andExpect(status().isAccepted())).get("id").asString();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from webhooks.deliveries where subscription_id = ?::uuid",
                Long.class, subscription)).isEqualTo(1);

        // once that one is finished, a new ping is queued again
        markSucceeded(UUID.fromString(first));
        String third = readJson(ping(merchant, subscription).andExpect(status().isAccepted())).get("id").asString();
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void aTurnedOffSubscriptionCanStillBePinged() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(PATH + "/" + subscription).cookie(merchant)).andExpect(status().isNoContent());

        ping(merchant, subscription).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventType").value("webhook.ping"));
    }

    @Test
    void anotherMerchantsSubscriptionIsNotFound() throws Exception {
        String subscription = subscribe(approvedMerchant());
        Cookie other = approvedMerchant();

        ping(other, subscription).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get(PATH + "/" + subscription + "/deliveries").cookie(other)).andExpect(status().isNotFound());
        ping(other, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    // ---------- history ----------

    @Test
    void historyIsNewestFirstAndCanBeFilteredByStatus() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID oldest = delivery(subscription, base.minusSeconds(300));
        UUID middle = delivery(subscription, base.minusSeconds(200));
        UUID newest = delivery(subscription, base.minusSeconds(100));
        markFailed(middle);

        mvc.perform(get(PATH + "/" + subscription + "/deliveries").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(newest.toString(), middle.toString(),
                        oldest.toString())))
                .andExpect(jsonPath("$.content[0].eventType").value("parcel.delivered"))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        mvc.perform(get(PATH + "/" + subscription + "/deliveries?status=FAILED").cookie(merchant))
                .andExpect(jsonPath("$.content[*].id", contains(middle.toString())))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].lastStatusCode").value(503))
                .andExpect(jsonPath("$.content[0].lastError").value("HTTP_503"))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").isEmpty());

        mvc.perform(get(PATH + "/" + subscription + "/deliveries?size=2&page=1").cookie(merchant))
                .andExpect(jsonPath("$.content[*].id", contains(oldest.toString())))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void badPagingOrStatusAnswers400() throws Exception {
        Cookie merchant = approvedMerchant();
        String path = PATH + "/" + subscribe(merchant) + "/deliveries";

        mvc.perform(get(path + "?page=-1").cookie(merchant)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.page").value("The page number can't be negative."));
        mvc.perform(get(path + "?size=0").cookie(merchant)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").value("Choose a page size between 1 and 100."));
        mvc.perform(get(path + "?size=101").cookie(merchant)).andExpect(status().isBadRequest());
        mvc.perform(get(path + "?status=LOST").cookie(merchant)).andExpect(status().isBadRequest());
    }

    // ---------- retry ----------

    @Test
    void aFailedDeliveryCanBeRetriedOnce() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID delivery = delivery(subscription, Instant.now().minusSeconds(60));
        markFailed(delivery);

        retry(merchant, delivery).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(delivery.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.nextAttemptAt").isNotEmpty())
                .andExpect(jsonPath("$.lastStatusCode").value(503))   // kept until the next attempt
                .andExpect(jsonPath("$.lastError").value("HTTP_503"));

        retry(merchant, delivery).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_DELIVERY_NOT_RETRYABLE"));
    }

    @Test
    void onlyFailedDeliveriesCanBeRetried() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID pending = delivery(subscription, Instant.now());
        UUID succeeded = delivery(subscription, Instant.now());
        markSucceeded(succeeded);

        retry(merchant, pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_DELIVERY_NOT_RETRYABLE"));
        retry(merchant, succeeded).andExpect(status().isConflict());
        assertThat(statusOf(succeeded)).isEqualTo("SUCCEEDED");
    }

    @Test
    void anotherMerchantsDeliveryIsNotFound() throws Exception {
        String subscription = subscribe(approvedMerchant());
        UUID delivery = delivery(subscription, Instant.now());
        markFailed(delivery);

        retry(approvedMerchant(), delivery).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        retry(approvedMerchant(), UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(statusOf(delivery)).isEqualTo("FAILED");
    }

    // ---------- admin ----------

    @Test
    void adminsSeeEveryMerchantsDeliveriesWithFilters() throws Exception {
        Cookie admin = loginAsAdmin();
        String subscription = subscribe(approvedMerchant());
        UUID merchantId = merchantOf(subscription);
        UUID failed = delivery(subscription, Instant.now().minusSeconds(10));
        UUID pending = delivery(subscription, Instant.now());
        markFailed(failed);

        mvc.perform(get(ADMIN_PATH + "?merchantId=" + merchantId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(pending.toString(), failed.toString())))
                .andExpect(jsonPath("$.content[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist());

        mvc.perform(get(ADMIN_PATH + "?status=FAILED&merchantId=" + merchantId).cookie(admin))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(failed.toString()));

        mvc.perform(get(ADMIN_PATH + "?status=FAILED").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("FAILED"))));

        mvc.perform(get(ADMIN_PATH + "?size=0").cookie(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").exists());
    }

    @Test
    void adminsCanRetryAnyMerchantsFailedDelivery() throws Exception {
        Cookie admin = loginAsAdmin();
        String subscription = subscribe(approvedMerchant());
        UUID delivery = delivery(subscription, Instant.now());
        markFailed(delivery);

        mvc.perform(post(ADMIN_PATH + "/" + delivery + "/retry").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.merchantId").value(merchantOf(subscription).toString()));

        mvc.perform(post(ADMIN_PATH + "/" + delivery + "/retry").cookie(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_DELIVERY_NOT_RETRYABLE"));
        mvc.perform(post(ADMIN_PATH + "/" + UUID.randomUUID() + "/retry").cookie(admin))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private Cookie approvedMerchant() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        return tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
    }

    /** A subscription made through the API; returns its id. */
    private String subscribe(Cookie merchant) throws Exception {
        return readJson(postJson(PATH, Map.of("url", URL, "eventTypes", List.of("parcel.delivered")), merchant)
                .andExpect(status().isCreated())).get("id").asString();
    }

    private UUID merchantOf(String subscription) {
        return jdbc.queryForObject("select merchant_id from webhooks.subscriptions where id = ?::uuid", UUID.class,
                subscription);
    }

    /** A PENDING parcel delivery for this subscription, created at {@code createdAt}. */
    private UUID delivery(String subscription, Instant createdAt) {
        return deliveries.saveAndFlush(WebhookDelivery.pending(UUID.randomUUID(), UUID.fromString(subscription),
                merchantOf(subscription), WebhookEventType.PARCEL_DELIVERED, "{\"id\":\"x\"}",
                createdAt.truncatedTo(ChronoUnit.MICROS))).getId();
    }

    private void markFailed(UUID id) {
        jdbc.update("update webhooks.deliveries set status = 'FAILED', attempts = 6, last_status_code = 503,"
                + " last_error = 'HTTP_503', next_attempt_at = null where id = ?", id);
    }

    private void markSucceeded(UUID id) {
        jdbc.update("update webhooks.deliveries set status = 'SUCCEEDED', attempts = 1, last_status_code = 200,"
                + " next_attempt_at = null, delivered_at = now() where id = ?", id);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from webhooks.deliveries where id = ?", String.class, id);
    }

    private ResultActions ping(Cookie merchant, String subscription) throws Exception {
        return mvc.perform(post(PATH + "/" + subscription + "/test").cookie(merchant));
    }

    private ResultActions retry(Cookie merchant, UUID delivery) throws Exception {
        return mvc.perform(post(PATH + "/deliveries/" + delivery + "/retry").cookie(merchant)
                .contentType(MediaType.APPLICATION_JSON));
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
