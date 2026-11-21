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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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

        // once that one is finished (and the 10-second wait between tests is over), a new ping is queued again
        markSucceeded(UUID.fromString(first));
        backdate(UUID.fromString(first), 11);
        String third = readJson(ping(merchant, subscription).andExpect(status().isAccepted())).get("id").asString();
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void sendTestRightAfterAFinishedPingAnswers429WithRetryAfter() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID first = UUID.fromString(readJson(ping(merchant, subscription).andExpect(status().isAccepted()))
                .get("id").asString());
        markFailed(first);

        String retryAfter = ping(merchant, subscription)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value("Too many attempts. Please wait a moment and try again."))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 10);

        backdate(first, 11);
        ping(merchant, subscription).andExpect(status().isAccepted());
    }

    @Test
    void aMerchantCanAskForAtMost30TestsAndRetriesAMinute() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        for (int i = 0; i < 30; i++) {
            ping(merchant, subscription).andExpect(status().isAccepted()); // the same waiting ping each time
        }

        String retryAfter = ping(merchant, subscription)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);
        UUID failed = delivery(subscription, Instant.now().minusSeconds(60));
        markFailed(failed);
        retry(merchant, failed).andExpect(status().isTooManyRequests()); // retries count too

        // another merchant isn't affected
        Cookie other = approvedMerchant();
        ping(other, subscribe(other)).andExpect(status().isAccepted());
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

    // ---------- history: event type filter ----------

    @Test
    void historyCanBeFilteredByEventType() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        Instant base = Instant.now();
        UUID olderDelivered = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, base.minusSeconds(300));
        delivery(subscription, WebhookEventType.PARCEL_FAILED, base.minusSeconds(200));
        UUID newerDelivered = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, base.minusSeconds(100));

        mvc.perform(get(PATH + "/" + subscription + "/deliveries?eventType=parcel.delivered").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(newerDelivered.toString(), olderDelivered.toString())))
                .andExpect(jsonPath("$.content[*].eventType", everyItem(is("parcel.delivered"))))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void testPingsCanBeFoundWithTheirEventType() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now().minusSeconds(60));
        String ping = readJson(mvc.perform(post(PATH + "/" + subscription + "/test").cookie(merchant))
                .andExpect(status().isAccepted())).get("id").asString();

        mvc.perform(get(PATH + "/" + subscription + "/deliveries?eventType=webhook.ping").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(ping)))
                .andExpect(jsonPath("$.content[0].eventType").value("webhook.ping"));
    }

    @Test
    void statusAndEventTypeFiltersCombine() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID failedFailure = delivery(subscription, WebhookEventType.PARCEL_FAILED, Instant.now().minusSeconds(300));
        delivery(subscription, WebhookEventType.PARCEL_FAILED, Instant.now().minusSeconds(200));      // still PENDING
        UUID failedDelivered = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now().minusSeconds(100));
        markFailed(failedFailure);
        markFailed(failedDelivered);

        mvc.perform(get(PATH + "/" + subscription + "/deliveries?status=FAILED&eventType=parcel.failed")
                        .cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(failedFailure.toString())));
    }

    @Test
    void noEventTypeOrAnEmptyOneMeansAllEvents() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now().minusSeconds(200));
        delivery(subscription, WebhookEventType.PARCEL_FAILED, Instant.now().minusSeconds(100));

        mvc.perform(get(PATH + "/" + subscription + "/deliveries").cookie(merchant))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(PATH + "/" + subscription + "/deliveries?eventType=").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void anUnknownEventTypeAnswers400() throws Exception {
        Cookie merchant = approvedMerchant();
        String path = PATH + "/" + subscribe(merchant) + "/deliveries";

        mvc.perform(get(path + "?eventType=parcel.lost").cookie(merchant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.eventType").value("'parcel.lost' isn't a webhook event"));
        // the enum name is not a wire name
        mvc.perform(get(path + "?eventType=PARCEL_DELIVERED").cookie(merchant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventType").exists());
    }

    // ---------- history: search and dates ----------

    @Test
    void aTrackingNumberFindsEveryDeliveryOfThatParcelEvenWrittenLoosely() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID parcel = UUID.randomUUID();
        Instant base = Instant.now();
        UUID pickedUp = parcelDelivery(subscription, WebhookEventType.PARCEL_PICKED_UP, base.minusSeconds(300),
                parcel, "SD2611000777", "TECH-ORD-1001");
        UUID outForDelivery = parcelDelivery(subscription, WebhookEventType.PARCEL_OUT_FOR_DELIVERY,
                base.minusSeconds(200), parcel, "SD2611000777", "TECH-ORD-1001");
        UUID delivered = parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, base.minusSeconds(100),
                parcel, "SD2611000777", "TECH-ORD-1001");
        parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, base, UUID.randomUUID(), "SD2611000888",
                "TECH-ORD-2002");
        String[] expected = {delivered.toString(), outForDelivery.toString(), pickedUp.toString()};

        for (String term : new String[] {"SD2611000777", "sd 2611 0007 77", "  sd2611000777  "}) {
            searchHistory(merchant, subscription, term)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", contains(expected)))
                    .andExpect(jsonPath("$.content[0].trackingNumber").value("SD2611000777"))
                    .andExpect(jsonPath("$.content[0].merchantOrderRef").value("TECH-ORD-1001"));
        }
    }

    @Test
    void partOfTheOrderNumberMatchesIgnoringCase() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID first = parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now().minusSeconds(60),
                UUID.randomUUID(), "SD2611000101", "TECH-ORD-1001");
        UUID second = parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now(),
                UUID.randomUUID(), "SD2611000102", "TECH-ORD-2002");

        history(merchant, subscription, "search=ord-1001")
                .andExpect(jsonPath("$.content[*].id", contains(first.toString())));
        history(merchant, subscription, "search=tech-ORD")
                .andExpect(jsonPath("$.content[*].id", contains(second.toString(), first.toString())));
    }

    @Test
    void wildcardCharactersInTheSearchAreTakenLiterally() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID literal = parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now().minusSeconds(60),
                UUID.randomUUID(), "SD2611000201", "SALE_50%");
        parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now(), UUID.randomUUID(),
                "SD2611000202", "SALEX50Y");

        searchHistory(merchant, subscription, "sale_50%")
                .andExpect(jsonPath("$.content[*].id", contains(literal.toString())));
    }

    @Test
    void anIdFindsExactlyItsDeliveryEventOrParcel() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID parcel = UUID.randomUUID();
        UUID older = parcelDelivery(subscription, WebhookEventType.PARCEL_PICKED_UP, Instant.now().minusSeconds(60),
                parcel, "SD2611000301", null);
        UUID newer = parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now(), parcel,
                "SD2611000301", null);
        String newerEvent = jdbc.queryForObject("select event_id::text from webhooks.deliveries where id = ?",
                String.class, newer);

        history(merchant, subscription, "search=" + older)
                .andExpect(jsonPath("$.content[*].id", contains(older.toString())));
        history(merchant, subscription, "search=" + newerEvent.toUpperCase())
                .andExpect(jsonPath("$.content[*].id", contains(newer.toString())));
        history(merchant, subscription, "search=" + parcel)
                .andExpect(jsonPath("$.content[*].id", contains(newer.toString(), older.toString())));
        history(merchant, subscription, "search=" + UUID.randomUUID())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void testPingsHaveNoParcelSoOnlyTheirIdFindsThem() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        JsonNode ping = readJson(mvc.perform(post(PATH + "/" + subscription + "/test").cookie(merchant))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackingNumber").isEmpty())
                .andExpect(jsonPath("$.merchantOrderRef").isEmpty()));

        history(merchant, subscription, "search=SD").andExpect(jsonPath("$.totalElements").value(0));
        history(merchant, subscription, "search=" + ping.get("id").asString())
                .andExpect(jsonPath("$.content[*].id", contains(ping.get("id").asString())));
    }

    @Test
    void searchCombinesWithStatusAndEventType() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID parcel = UUID.randomUUID();
        UUID failedDelivery = parcelDelivery(subscription, WebhookEventType.PARCEL_FAILED,
                Instant.now().minusSeconds(300), parcel, "SD2611000401", "ORD-401");
        UUID pendingFailure = parcelDelivery(subscription, WebhookEventType.PARCEL_FAILED,
                Instant.now().minusSeconds(200), parcel, "SD2611000401", "ORD-401");
        UUID failedPickup = parcelDelivery(subscription, WebhookEventType.PARCEL_PICKED_UP,
                Instant.now().minusSeconds(100), parcel, "SD2611000401", "ORD-401");
        markFailed(failedDelivery);
        markFailed(failedPickup);

        history(merchant, subscription, "search=SD2611000401&status=FAILED")
                .andExpect(jsonPath("$.content[*].id", contains(failedPickup.toString(), failedDelivery.toString())));
        history(merchant, subscription, "search=SD2611000401&eventType=parcel.failed")
                .andExpect(jsonPath("$.content[*].id", contains(pendingFailure.toString(), failedDelivery.toString())));
        history(merchant, subscription, "search=ORD-401&status=FAILED&eventType=parcel.failed")
                .andExpect(jsonPath("$.content[*].id", contains(failedDelivery.toString())));
    }

    @Test
    void dateRangeUsesWholeSriLankanDaysWithBothEndsIncluded() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        // 10 Nov 2026 in Sri Lanka (UTC+05:30) runs from 2026-11-09T18:30:00Z to 2026-11-10T18:29:59.999Z
        UUID beforeStart = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.parse("2026-11-09T18:29:59Z"));
        UUID atStart = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.parse("2026-11-09T18:30:00Z"));
        UUID atEnd = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.parse("2026-11-10T18:29:59Z"));
        UUID afterEnd = delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.parse("2026-11-10T18:30:00Z"));

        history(merchant, subscription, "from=2026-11-10&to=2026-11-10")
                .andExpect(jsonPath("$.content[*].id", contains(atEnd.toString(), atStart.toString())));
        history(merchant, subscription, "from=2026-11-10")
                .andExpect(jsonPath("$.content[*].id", contains(afterEnd.toString(), atEnd.toString(),
                        atStart.toString())));
        history(merchant, subscription, "to=2026-11-10")
                .andExpect(jsonPath("$.content[*].id", contains(atEnd.toString(), atStart.toString(),
                        beforeStart.toString())));
    }

    @Test
    void everyFilterTogetherFindsTheOneMatchingDelivery() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID parcel = UUID.randomUUID();
        UUID match = parcelDelivery(subscription, WebhookEventType.PARCEL_FAILED, Instant.parse("2026-11-12T06:00:00Z"),
                parcel, "SD2611000501", "ORD-501");
        UUID otherDay = parcelDelivery(subscription, WebhookEventType.PARCEL_FAILED,
                Instant.parse("2026-11-14T06:00:00Z"), parcel, "SD2611000501", "ORD-501");
        parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.parse("2026-11-12T07:00:00Z"), parcel,
                "SD2611000501", "ORD-501");
        markFailed(match);
        markFailed(otherDay);

        history(merchant, subscription,
                "search=sd2611000501&status=FAILED&eventType=parcel.failed&from=2026-11-12&to=2026-11-13&size=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(match.toString())))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void badSearchOrDatesAnswer400WithTheField() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);

        history(merchant, subscription, "from=2026-11-15&to=2026-11-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.to").value("The end date can't be before the start date."));
        history(merchant, subscription, "from=2026-13-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.from").value("Enter a date like 2026-11-15."));
        history(merchant, subscription, "to=15-11-2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").value("Enter a date like 2026-11-15."));
        history(merchant, subscription, "search=" + "x".repeat(65)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.search").value("Search with at most 64 characters."));
    }

    @Test
    void anotherMerchantsParcelWithTheSameTrackingNumberIsNeverShown() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        Cookie other = approvedMerchant();
        String otherSubscription = subscribe(other);
        UUID mine = parcelDelivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now(), UUID.randomUUID(),
                "SD2611000601", "ORD-601");
        UUID theirs = parcelDelivery(otherSubscription, WebhookEventType.PARCEL_DELIVERED, Instant.now(),
                UUID.randomUUID(), "SD2611000601", "ORD-601");

        history(merchant, subscription, "search=SD2611000601")
                .andExpect(jsonPath("$.content[*].id", contains(mine.toString())));
        history(merchant, subscription, "search=" + theirs)
                .andExpect(jsonPath("$.totalElements").value(0));
        // and the other merchant's history itself stays out of reach
        history(merchant, otherSubscription, "search=SD2611000601").andExpect(status().isNotFound());
    }

    @Test
    void adminsCanSearchAcrossMerchantsOrWithinOne() throws Exception {
        Cookie admin = loginAsAdmin();
        String first = subscribe(approvedMerchant());
        String second = subscribe(approvedMerchant());
        String tracking = "SD26" + (System.nanoTime() % 100_000_000L); // unique across test runs on the shared database
        UUID a = parcelDelivery(first, WebhookEventType.PARCEL_DELIVERED, Instant.now().minusSeconds(60),
                UUID.randomUUID(), tracking, null);
        UUID b = parcelDelivery(second, WebhookEventType.PARCEL_DELIVERED, Instant.now(), UUID.randomUUID(),
                tracking, null);

        mvc.perform(get(ADMIN_PATH + "?search=" + tracking).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(b.toString(), a.toString())))
                .andExpect(jsonPath("$.content[0].trackingNumber").value(tracking));
        mvc.perform(get(ADMIN_PATH + "?search=" + tracking + "&merchantId=" + merchantOf(first)).cookie(admin))
                .andExpect(jsonPath("$.content[*].id", contains(a.toString())));
        mvc.perform(get(ADMIN_PATH + "?from=2026-11-15&to=2026-11-01").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").exists());
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
    void aDeliveryThatJustFailedCanBeRetriedAfterTenSecondsButAdminsNeedNotWait() throws Exception {
        Cookie merchant = approvedMerchant();
        String subscription = subscribe(merchant);
        UUID delivery = delivery(subscription, Instant.now());
        markFailed(delivery); // its last attempt was just now

        String retryAfter = retry(merchant, delivery)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 10);
        assertThat(statusOf(delivery)).isEqualTo("FAILED");

        mvc.perform(post(ADMIN_PATH + "/" + delivery + "/retry").cookie(loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void theCooldownNeverHidesThatADeliveryIsntFailed() throws Exception {
        Cookie merchant = approvedMerchant();
        UUID pending = delivery(subscribe(merchant), Instant.now()); // updated just now, but not FAILED

        retry(merchant, pending).andExpect(status().isConflict())
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
    void adminsCanFilterByEventTypeToo() throws Exception {
        Cookie admin = loginAsAdmin();
        String subscription = subscribe(approvedMerchant());
        UUID merchantId = merchantOf(subscription);
        UUID failure = delivery(subscription, WebhookEventType.PARCEL_FAILED, Instant.now().minusSeconds(100));
        delivery(subscription, WebhookEventType.PARCEL_DELIVERED, Instant.now());

        mvc.perform(get(ADMIN_PATH + "?merchantId=" + merchantId + "&eventType=parcel.failed").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(failure.toString())))
                .andExpect(jsonPath("$.content[0].merchantId").value(merchantId.toString()));

        mvc.perform(get(ADMIN_PATH + "?eventType=parcel.lost").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventType").value("'parcel.lost' isn't a webhook event"));
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
        return delivery(subscription, WebhookEventType.PARCEL_DELIVERED, createdAt);
    }

    /** A PENDING parcel-event delivery with its searchable parcel fields (what the fan-out stores). */
    private UUID parcelDelivery(String subscription, WebhookEventType type, Instant createdAt, UUID parcelId,
                                String trackingNumber, String merchantOrderRef) {
        return deliveries.saveAndFlush(WebhookDelivery.pending(UUID.randomUUID(), UUID.fromString(subscription),
                        merchantOf(subscription), type, "{\"id\":\"x\"}", createdAt.truncatedTo(ChronoUnit.MICROS))
                .forParcel(parcelId, trackingNumber, merchantOrderRef)).getId();
    }

    private ResultActions history(Cookie merchant, String subscription, String query) throws Exception {
        return mvc.perform(get(PATH + "/" + subscription + "/deliveries?" + query).cookie(merchant));
    }

    /** The search term as a real request parameter (spaces and % sent as typed, not as a URL template). */
    private ResultActions searchHistory(Cookie merchant, String subscription, String term) throws Exception {
        return mvc.perform(get(PATH + "/" + subscription + "/deliveries").param("search", term).cookie(merchant));
    }

    /** A PENDING delivery of {@code type} for this subscription, created at {@code createdAt}. */
    private UUID delivery(String subscription, WebhookEventType type, Instant createdAt) {
        return deliveries.saveAndFlush(WebhookDelivery.pending(UUID.randomUUID(), UUID.fromString(subscription),
                merchantOf(subscription), type, "{\"id\":\"x\"}", createdAt.truncatedTo(ChronoUnit.MICROS))).getId();
    }

    private void markFailed(UUID id) {
        jdbc.update("update webhooks.deliveries set status = 'FAILED', attempts = 6, last_status_code = 503,"
                + " last_error = 'HTTP_503', next_attempt_at = null where id = ?", id);
    }

    /** Moves a delivery's creation and last change {@code seconds} into the past (past the cooldown). */
    private void backdate(UUID id, int seconds) {
        jdbc.update("update webhooks.deliveries set created_at = created_at - make_interval(secs => ?),"
                + " updated_at = updated_at - make_interval(secs => ?) where id = ?", seconds, seconds, id);
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
