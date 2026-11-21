package com.swiftdrop.webhooks;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.webhooks.internal.security.SecretCipher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant webhook subscriptions (API.md "Webhooks"). Host names are resolved by FakeHostResolver:
 * shop.example.com is public, api.private.test is 10.0.0.5.
 */
@ExtendWith(OutputCaptureExtension.class)
class MerchantWebhookApiTest extends IntegrationTest {

    private static final String PATH = "/api/v1/merchant/webhooks";
    private static final String URL = "https://shop.example.com/swiftdrop";
    private static final String SECRET_PATTERN = "whsec_[A-Za-z0-9_-]{43}";
    private static final String INVALID_URL = "Enter a full https:// address, like https://shop.example.com/webhooks";
    private static final String NOT_ALLOWED = "This address can't receive webhooks. Use a public https:// address.";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SecretCipher cipher;

    @Autowired
    IdentityApi identity;

    // ---------- authorization ----------

    @Test
    void withoutLoginEveryEndpointAnswers401() throws Exception {
        UUID id = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(get(PATH), post(PATH), patch(PATH + "/" + id),
                delete(PATH + "/" + id), post(PATH + "/" + id + "/rotate-secret"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void otherRolesGet403() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String courierEmail = uniqueEmail();
        identity.registerUser("Kamal Jayawardena", courierEmail, stored(uniquePhone()), PASSWORD, Role.COURIER,
                UserStatus.ACTIVE);
        Cookie courier = tokenCookie(login(courierEmail, PASSWORD, false).andExpect(status().isOk()).andReturn());

        for (Cookie cookie : List.of(customer, courier, loginAsAdmin())) {
            mvc.perform(get(PATH).cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void apiKeyRequestsGet403NotAServerError() throws Exception {
        Cookie merchant = approvedMerchant();
        String rawKey = json.readTree(postJson("/api/v1/merchant/api-keys", Map.of("name", "shop"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("rawKey").asString();

        mvc.perform(get(PATH).header("X-Api-Key", rawKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post(PATH).header("X-Api-Key", rawKey).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(URL, List.of("parcel.delivered")))))
                .andExpect(status().isForbidden());
    }

    // ---------- create ----------

    @Test
    void createReturnsTheSecretOnceAndStoresItEncrypted() throws Exception {
        Cookie merchant = approvedMerchant();

        JsonNode created = readJson(create(merchant, URL, List.of("parcel.failed", "parcel.delivered"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.eventTypes", contains("parcel.delivered", "parcel.failed")))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.secret").value(org.hamcrest.Matchers.matchesPattern(SECRET_PATTERN))));

        String secret = created.get("secret").asString();
        String stored = storedCiphertext(created.get("id").asString());
        assertThat(stored).startsWith("v1:").doesNotContain(secret).doesNotContain("whsec_");
        assertThat(cipher.decrypt(stored)).isEqualTo(secret);
    }

    @Test
    void invalidBodiesAnswer400WithFieldErrors() throws Exception {
        Cookie merchant = approvedMerchant();

        create(merchant, null, List.of("parcel.delivered")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.url").value(INVALID_URL));
        create(merchant, URL, List.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventTypes").value("Choose at least one event"));
        create(merchant, URL, List.of("parcel.lost")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventTypes").value("'parcel.lost' isn't a webhook event"));
        create(merchant, URL, List.of("webhook.ping")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventTypes").value("'webhook.ping' isn't a webhook event"));
        assertThat(count(merchant)).isZero();
    }

    @Test
    void unsafeUrlsAreRejected() throws Exception {
        Cookie merchant = approvedMerchant();

        create(merchant, "http://shop.example.com/x", List.of("parcel.delivered"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.url").value(INVALID_URL));
        for (String url : List.of("https://api.private.test/x", "https://169.254.169.254/latest",
                "https://127.0.0.1/x", "https://user:pw@shop.example.com/x")) {
            create(merchant, url, List.of("parcel.delivered"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.url").value(NOT_ALLOWED));
        }
        assertThat(count(merchant)).isZero();
    }

    @Test
    void aSixthActiveSubscriptionIsRefused() throws Exception {
        Cookie merchant = approvedMerchant();
        for (int i = 0; i < 5; i++) {
            create(merchant, URL + "/" + i, List.of("parcel.delivered")).andExpect(status().isCreated());
        }

        create(merchant, URL + "/6", List.of("parcel.delivered"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_LIMIT_REACHED"));
    }

    @Test
    void turnedOffSubscriptionsDontCountTowardsTheLimit() throws Exception {
        Cookie merchant = approvedMerchant();
        String first = createdId(merchant);
        for (int i = 0; i < 4; i++) {
            createdId(merchant, "/" + i); // one active subscription per URL
        }
        mvc.perform(delete(PATH + "/" + first).cookie(merchant)).andExpect(status().isNoContent());

        create(merchant, URL + "/new", List.of("parcel.delivered")).andExpect(status().isCreated());
        // 5 active again: turning the first one back on is refused
        update(merchant, first, Map.of("active", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_LIMIT_REACHED"));
    }

    // ---------- list ----------

    @Test
    void listShowsOnlyYourSubscriptionsNewestFirstWithoutSecrets() throws Exception {
        Cookie merchant = approvedMerchant();
        String older = createdId(merchant, "/older");
        String newer = createdId(merchant, "/newer");
        createdId(approvedMerchant()); // another merchant's

        mvc.perform(get(PATH).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[1].id").value(older))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[1].secret").doesNotExist());
    }

    // ---------- update ----------

    @Test
    void updateChangesOnlyTheFieldsSent() throws Exception {
        Cookie merchant = approvedMerchant();
        JsonNode created = readJson(create(merchant, URL, List.of("parcel.delivered")));
        String id = created.get("id").asString();

        JsonNode updated = readJson(update(merchant, id, Map.of("url", "https://shop.example.com/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://shop.example.com/v2"))
                .andExpect(jsonPath("$.eventTypes", contains("parcel.delivered")))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.secret").doesNotExist()));
        assertThat(Instant.parse(updated.get("updatedAt").asString()))
                .isAfter(Instant.parse(created.get("updatedAt").asString()));

        update(merchant, id, Map.of("eventTypes", List.of("parcel.picked_up", "parcel.failed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://shop.example.com/v2"))
                .andExpect(jsonPath("$.eventTypes", contains("parcel.picked_up", "parcel.failed")));
    }

    @Test
    void updateCanTurnASubscriptionOffAndOnAgain() throws Exception {
        Cookie merchant = approvedMerchant();
        String id = createdId(merchant);

        update(merchant, id, Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        update(merchant, id, Map.of("active", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void updateValidatesLikeCreate() throws Exception {
        Cookie merchant = approvedMerchant();
        String id = createdId(merchant);

        update(merchant, id, Map.of("eventTypes", List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventTypes").value("Choose at least one event"));
        update(merchant, id, Map.of("url", "https://api.private.test/x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").value(NOT_ALLOWED));
        update(merchant, id, Map.of("url", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.url").value(INVALID_URL));

        mvc.perform(get(PATH).cookie(merchant)).andExpect(jsonPath("$[0].url").value(URL)); // unchanged
    }

    // ---------- delete ----------

    @Test
    void deleteTurnsItOffKeepsTheRowAndCanBeRepeated() throws Exception {
        Cookie merchant = approvedMerchant();
        String id = createdId(merchant);

        mvc.perform(delete(PATH + "/" + id).cookie(merchant)).andExpect(status().isNoContent());
        mvc.perform(delete(PATH + "/" + id).cookie(merchant)).andExpect(status().isNoContent());

        mvc.perform(get(PATH).cookie(merchant))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].active").value(false));
        assertThat(jdbc.queryForObject("select active from webhooks.subscriptions where id = ?::uuid",
                Boolean.class, id)).isFalse();
    }

    // ---------- rotate ----------

    @Test
    void rotateGivesANewSecretAndStoresIt() throws Exception {
        Cookie merchant = approvedMerchant();
        JsonNode created = readJson(create(merchant, URL, List.of("parcel.delivered")));
        String id = created.get("id").asString();

        JsonNode rotated = readJson(mvc.perform(post(PATH + "/" + id + "/rotate-secret").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.secret").value(org.hamcrest.Matchers.matchesPattern(SECRET_PATTERN))));

        String newSecret = rotated.get("secret").asString();
        assertThat(newSecret).isNotEqualTo(created.get("secret").asString());
        assertThat(cipher.decrypt(storedCiphertext(id))).isEqualTo(newSecret);
    }

    // ---------- ownership ----------

    @Test
    void anotherMerchantsSubscriptionIsNotFound() throws Exception {
        Cookie owner = approvedMerchant();
        String id = createdId(owner);
        Cookie other = approvedMerchant();

        update(other, id, Map.of("active", false)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete(PATH + "/" + id).cookie(other)).andExpect(status().isNotFound());
        mvc.perform(post(PATH + "/" + id + "/rotate-secret").cookie(other)).andExpect(status().isNotFound());

        mvc.perform(get(PATH).cookie(owner)).andExpect(jsonPath("$[0].active").value(true)); // untouched
    }

    @Test
    void unknownAndMalformedIds() throws Exception {
        Cookie merchant = approvedMerchant();

        mvc.perform(delete(PATH + "/" + UUID.randomUUID()).cookie(merchant)).andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/not-a-uuid").cookie(merchant)).andExpect(status().isBadRequest());
    }

    // ---------- one active subscription per callback URL (per merchant) ----------

    private static final String URL_IN_USE = "You already have a webhook for this address. Add the events to it instead.";

    @Test
    void aSecondActiveSubscriptionForTheSameUrlIsRefused() throws Exception {
        Cookie merchant = approvedMerchant();
        create(merchant, URL, List.of("parcel.delivered")).andExpect(status().isCreated());

        create(merchant, URL, List.of("parcel.failed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_URL_IN_USE"))
                .andExpect(jsonPath("$.message").value(URL_IN_USE));
        // the same destination written differently: host letter case, default port, a fragment
        create(merchant, "HTTPS://SHOP.Example.com:443/swiftdrop#orders", List.of("parcel.failed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_URL_IN_USE"));

        assertThat(count(merchant)).isEqualTo(1);
    }

    @Test
    void aDifferentPathQueryOrPortIsADifferentDestination() throws Exception {
        Cookie merchant = approvedMerchant();
        create(merchant, URL, List.of("parcel.delivered")).andExpect(status().isCreated());

        create(merchant, URL + "/", List.of("parcel.delivered")).andExpect(status().isCreated());
        create(merchant, URL + "?env=staging", List.of("parcel.delivered")).andExpect(status().isCreated());
        create(merchant, "https://shop.example.com:8443/swiftdrop", List.of("parcel.delivered"))
                .andExpect(status().isCreated());

        assertThat(count(merchant)).isEqualTo(4);
    }

    @Test
    void anotherMerchantMayUseTheSameUrl() throws Exception {
        create(approvedMerchant(), URL, List.of("parcel.delivered")).andExpect(status().isCreated());

        create(approvedMerchant(), URL, List.of("parcel.delivered")).andExpect(status().isCreated());
    }

    @Test
    void aTurnedOffSubscriptionDoesNotBlockItsUrlButCantBeTurnedOnAgainWhileItIsInUse() throws Exception {
        Cookie merchant = approvedMerchant();
        String old = createdId(merchant);
        mvc.perform(delete(PATH + "/" + old).cookie(merchant)).andExpect(status().isNoContent());

        String replacement = createdId(merchant); // same URL: the old one is off
        update(merchant, old, Map.of("active", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_URL_IN_USE"));
        mvc.perform(get(PATH).cookie(merchant))
                .andExpect(jsonPath("$[?(@.id == '" + old + "')].active").value(false));

        // once the replacement is off, the old one can come back
        mvc.perform(delete(PATH + "/" + replacement).cookie(merchant)).andExpect(status().isNoContent());
        update(merchant, old, Map.of("active", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void changingTheUrlToOneInUseIsRefusedButItsOwnUrlIsFine() throws Exception {
        Cookie merchant = approvedMerchant();
        String first = createdId(merchant);
        String second = createdId(merchant, "/billing");

        update(merchant, second, Map.of("url", URL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_URL_IN_USE"));
        // its own URL, written differently, isn't "in use by another"
        update(merchant, first, Map.of("url", "https://SHOP.example.com/swiftdrop")).andExpect(status().isOk());
        // turning a subscription on and moving it to a free URL in one request
        mvc.perform(delete(PATH + "/" + second).cookie(merchant)).andExpect(status().isNoContent());
        update(merchant, second, Map.of("active", true, "url", URL + "/support")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aTurnedOffSubscriptionCanMoveToAUrlInUse() throws Exception {
        Cookie merchant = approvedMerchant();
        createdId(merchant);
        String other = createdId(merchant, "/other");
        mvc.perform(delete(PATH + "/" + other).cookie(merchant)).andExpect(status().isNoContent());

        // it stays off, so it doesn't compete for the URL (turning it on later would be refused)
        update(merchant, other, Map.of("url", URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void existingDuplicatesKeepWorkingAndOnlyTheirEventsCanBeEdited() throws Exception {
        Cookie merchant = approvedMerchant();
        createdId(merchant);
        String legacy = createdId(merchant, "/legacy");
        // a duplicate made before this rule existed
        jdbc.update("update webhooks.subscriptions set url = ? where id = ?::uuid", URL, legacy);

        update(merchant, legacy, Map.of("eventTypes", List.of("parcel.failed"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTypes", contains("parcel.failed")));
        mvc.perform(post(PATH + "/" + legacy + "/rotate-secret").cookie(merchant)).andExpect(status().isOk());
    }

    // ---------- secrets never reach the logs ----------

    @Test
    void secretsAndCiphertextsAreNeverLogged(CapturedOutput output) throws Exception {
        Cookie merchant = approvedMerchant();
        JsonNode created = readJson(create(merchant, URL, List.of("parcel.delivered")));
        String id = created.get("id").asString();
        JsonNode rotated = readJson(mvc.perform(post(PATH + "/" + id + "/rotate-secret").cookie(merchant)));

        assertThat(output.getAll())
                .doesNotContain(created.get("secret").asString())
                .doesNotContain(rotated.get("secret").asString())
                .doesNotContain(storedCiphertext(id));
    }

    // ---------- requests at the same moment ----------

    @Test
    void parallelCreatesNeverPassTheLimitOfFive() throws Exception {
        Cookie merchant = approvedMerchant();
        for (int i = 0; i < 4; i++) {
            createdId(merchant, "/" + i);
        }

        List<MvcResult> results = atOnce(List.of(
                () -> create(merchant, URL + "/a", List.of("parcel.delivered")).andReturn(),
                () -> create(merchant, URL + "/b", List.of("parcel.delivered")).andReturn(),
                () -> create(merchant, URL + "/c", List.of("parcel.delivered")).andReturn()));

        assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409, 409);
        assertThat(codes(results)).containsOnly("WEBHOOK_LIMIT_REACHED");
        assertThat(activeCount(merchant)).isEqualTo(5);
    }

    @Test
    void parallelCreatesForTheSameUrlMakeOneSubscription() throws Exception {
        Cookie merchant = approvedMerchant();

        List<MvcResult> results = atOnce(List.of(
                () -> create(merchant, URL, List.of("parcel.delivered")).andReturn(),
                () -> create(merchant, URL, List.of("parcel.failed")).andReturn()));

        assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409);
        assertThat(codes(results)).containsOnly("WEBHOOK_URL_IN_USE");
        assertThat(activeCount(merchant)).isEqualTo(1);
    }

    /**
     * Two tabs saving at once. Whether the two requests really overlap depends on timing, so the test accepts both
     * orders, but never a 500: the loser of a real clash gets WEBHOOK_CHANGED and the stored events are one request's.
     */
    @Test
    void parallelUpdatesNeverAnswer500() throws Exception {
        Cookie merchant = approvedMerchant();
        String id = createdId(merchant);

        List<MvcResult> results = atOnce(List.of(
                () -> update(merchant, id, Map.of("eventTypes", List.of("parcel.picked_up"))).andReturn(),
                () -> update(merchant, id, Map.of("eventTypes", List.of("parcel.failed"))).andReturn()));

        assertThat(statuses(results)).containsAnyOf(200).allMatch(code -> code == 200 || code == 409);
        assertThat(codes(results)).allMatch("WEBHOOK_CHANGED"::equals);
        String stored = jdbc.queryForObject("select array_to_string(event_types, ',') from webhooks.subscriptions"
                + " where id = ?::uuid", String.class, id);
        assertThat(stored).isIn("parcel.picked_up", "parcel.failed");
    }

    @Test
    void parallelRotationsStoreTheSecretThatWasShown() throws Exception {
        Cookie merchant = approvedMerchant();
        String id = createdId(merchant);

        List<MvcResult> results = atOnce(List.of(
                () -> mvc.perform(post(PATH + "/" + id + "/rotate-secret").cookie(merchant)).andReturn(),
                () -> mvc.perform(post(PATH + "/" + id + "/rotate-secret").cookie(merchant)).andReturn()));

        assertThat(statuses(results)).containsAnyOf(200).allMatch(code -> code == 200 || code == 409);
        assertThat(codes(results)).allMatch("WEBHOOK_CHANGED"::equals);
        List<String> shown = new ArrayList<>();
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 200) {
                shown.add(json.readTree(result.getResponse().getContentAsString()).get("secret").asString());
            }
        }
        // a secret that lost the race is never shown; the stored one is one the merchant saw
        assertThat(cipher.decrypt(storedCiphertext(id))).isIn(shown);
    }

    @Test
    void turningOffTwiceAtOnceAnswers204Both() throws Exception {
        Cookie merchant = approvedMerchant();
        String id = createdId(merchant);

        List<MvcResult> results = atOnce(List.of(
                () -> mvc.perform(delete(PATH + "/" + id).cookie(merchant)).andReturn(),
                () -> mvc.perform(delete(PATH + "/" + id).cookie(merchant)).andReturn()));

        assertThat(statuses(results)).containsExactly(204, 204);
        assertThat(activeCount(merchant)).isZero();
    }

    // ---------- tokens that must not work ----------

    @Test
    void expiredOrTamperedTokensGet401AndChangeNothing() throws Exception {
        Cookie merchant = approvedMerchant();
        String[] parts = merchant.getValue().split("\\.");
        Map<String, Object> claims = json.readValue(Base64.getUrlDecoder().decode(parts[1]),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        String merchantId = (String) claims.get("sub");

        Map<String, Cookie> badTokens = new java.util.LinkedHashMap<>();
        badTokens.put("expired", new Cookie(COOKIE, sign(jwtEncoder, claims, Instant.now().minusSeconds(3600))));
        badTokens.put("signature changed", new Cookie(COOKIE, parts[0] + "." + parts[1] + "."
                + changeOneCharacter(parts[2])));
        Map<String, Object> asAdmin = new java.util.HashMap<>(claims);
        asAdmin.put("role", "ADMIN");
        badTokens.put("payload changed, old signature", new Cookie(COOKIE, parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(asAdmin)) + "."
                + parts[2]));
        JwtEncoder otherKey = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(
                "not-the-swiftdrop-secret-0123456789-abcdefghijklmnop".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256")).algorithm(MacAlgorithm.HS256).build();
        badTokens.put("signed with another key", new Cookie(COOKIE,
                sign(otherKey, claims, Instant.now().plusSeconds(600))));

        for (Map.Entry<String, Cookie> bad : badTokens.entrySet()) {
            mvc.perform(get(PATH).cookie(bad.getValue()))
                    .andExpect(status().isUnauthorized());
            create(bad.getValue(), URL, List.of("parcel.delivered"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("select count(*) from webhooks.subscriptions where merchant_id = ?::uuid",
                Long.class, merchantId)).as("nothing created with a bad token").isZero();
        // the real token still works
        mvc.perform(get(PATH).cookie(merchant)).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    @Autowired
    JwtEncoder jwtEncoder;

    /** Runs the requests at the same moment, each on its own thread, and returns their results in order. */
    private static List<MvcResult> atOnce(List<Callable<MvcResult>> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> statuses(List<MvcResult> results) {
        return results.stream().map(result -> result.getResponse().getStatus()).toList();
    }

    /** The error codes of the results that failed. */
    private List<String> codes(List<MvcResult> results) throws Exception {
        List<String> codes = new ArrayList<>();
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() >= 400) {
                codes.add(json.readTree(result.getResponse().getContentAsString()).get("code").asString());
            }
        }
        return codes;
    }

    private long activeCount(Cookie merchant) throws Exception {
        JsonNode list = json.readTree(mvc.perform(get(PATH).cookie(merchant)).andReturn().getResponse()
                .getContentAsString());
        long active = 0;
        for (JsonNode subscription : list) {
            if (subscription.get("active").asBoolean()) {
                active++;
            }
        }
        return active;
    }

    /** The same claims as a real token, with a new expiry, signed by {@code encoder}. */
    private static String sign(JwtEncoder encoder, Map<String, Object> claims, Instant expiresAt) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
        claims.forEach((name, value) -> {
            if (!"iat".equals(name) && !"exp".equals(name)) {
                builder.claim(name, value);
            }
        });
        builder.issuedAt(expiresAt.minusSeconds(600)).expiresAt(expiresAt);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
                .getTokenValue();
    }

    /** Changes a character in the middle (the last one may carry only padding bits). */
    private static String changeOneCharacter(String value) {
        int middle = value.length() / 2;
        char replacement = value.charAt(middle) == 'A' ? 'B' : 'A';
        return value.substring(0, middle) + replacement + value.substring(middle + 1);
    }

    /** Registers a merchant, approves them as admin and returns their cookie. */
    private Cookie approvedMerchant() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        return tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
    }

    private static Map<String, Object> body(String url, List<String> eventTypes) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("url", url);
        body.put("eventTypes", eventTypes);
        return body;
    }

    private ResultActions create(Cookie merchant, String url, List<String> eventTypes) throws Exception {
        return postJson(PATH, body(url, eventTypes), merchant);
    }

    private String createdId(Cookie merchant) throws Exception {
        return createdId(merchant, "");
    }

    /** A subscription for URL + {@code path}: a merchant can't have two active ones for the same URL. */
    private String createdId(Cookie merchant, String path) throws Exception {
        return readJson(create(merchant, URL + path, List.of("parcel.delivered")).andExpect(status().isCreated()))
                .get("id").asString();
    }

    private ResultActions update(Cookie merchant, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(patch(PATH + "/" + id).cookie(merchant).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String storedCiphertext(String id) {
        return jdbc.queryForObject("select secret_ciphertext from webhooks.subscriptions where id = ?::uuid",
                String.class, id);
    }

    private int count(Cookie merchant) throws Exception {
        return json.readTree(mvc.perform(get(PATH).cookie(merchant)).andReturn().getResponse().getContentAsString())
                .size();
    }
}
