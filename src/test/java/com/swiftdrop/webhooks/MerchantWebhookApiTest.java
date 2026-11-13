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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            createdId(merchant);
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
        String older = createdId(merchant);
        String newer = createdId(merchant);
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

    // ---------- helpers ----------

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
        return readJson(create(merchant, URL, List.of("parcel.delivered")).andExpect(status().isCreated()))
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
