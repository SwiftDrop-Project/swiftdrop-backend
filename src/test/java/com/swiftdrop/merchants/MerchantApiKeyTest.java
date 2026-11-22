package com.swiftdrop.merchants;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MerchantApiKeyTest extends IntegrationTest {

    private static final String KEYS_PATH = "/api/v1/merchant/api-keys";

    @Autowired
    JdbcTemplate jdbc;

    /** Registers a merchant, approves them, and returns a logged-in cookie. */
    private Cookie approveAndLogin(String email, String phone) throws Exception {
        String userId = registerMerchant(email, phone);
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        return tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get(KEYS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void customerGets403() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(KEYS_PATH).cookie(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void createKeyReturnsRawKeyOnce() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        mvc.perform(post(KEYS_PATH).cookie(merchant)
                        .contentType("application/json")
                        .content("{\"name\":\"prod\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("prod"))
                .andExpect(jsonPath("$.rawKey").value(startsWith("sd_")))
                .andExpect(jsonPath("$.rawKey", hasLength(35)))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void listDoesNotShowRawKey() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        postJson(KEYS_PATH, Map.of("name", "test-key"), merchant);

        mvc.perform(get(KEYS_PATH).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].name").value("test-key"))
                .andExpect(jsonPath("$[0].revokedAt").doesNotExist())
                .andExpect(jsonPath("$[0].rawKey").doesNotExist());
    }

    @Test
    void revokeKey() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        String keyId = postJson(KEYS_PATH, Map.of("name", "to-revoke"), merchant)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(keyId).get("id").asString();

        mvc.perform(delete(KEYS_PATH + "/{id}", id).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        mvc.perform(get(KEYS_PATH).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty());
    }

    @Test
    void revokeIsIdempotent() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        String responseBody = postJson(KEYS_PATH, Map.of("name", "idempotent"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(responseBody).get("id").asString();

        mvc.perform(delete(KEYS_PATH + "/{id}", id).cookie(merchant)).andExpect(status().isOk());
        mvc.perform(delete(KEYS_PATH + "/{id}", id).cookie(merchant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
    }

    @Test
    void rotateReturnsNewRawKey() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        String createBody = postJson(KEYS_PATH, Map.of("name", "rotate-me"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String oldId = json.readTree(createBody).get("id").asString();
        String oldRawKey = json.readTree(createBody).get("rawKey").asString();

        String rotateBody = mvc.perform(post(KEYS_PATH + "/{id}/rotate", oldId).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(not(oldId)))
                .andExpect(jsonPath("$.rawKey").value(startsWith("sd_")))
                .andExpect(jsonPath("$.name").value("rotate-me"))
                .andReturn().getResponse().getContentAsString();
        String newRawKey = json.readTree(rotateBody).get("rawKey").asString();

        // raw keys must differ
        assert !newRawKey.equals(oldRawKey);

        // old key must be revoked
        mvc.perform(get(KEYS_PATH).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + oldId + "')].revokedAt").isNotEmpty());
    }

    @Test
    void merchantNeverSeesAnotherMerchantsKeys() throws Exception {
        Cookie merchantA = approveAndLogin(uniqueEmail(), uniquePhone());
        Cookie merchantB = approveAndLogin(uniqueEmail(), uniquePhone());

        String bodyA = postJson(KEYS_PATH, Map.of("name", "key-a"), merchantA)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String idA = json.readTree(bodyA).get("id").asString();

        String bodyB = postJson(KEYS_PATH, Map.of("name", "key-b"), merchantB)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String idB = json.readTree(bodyB).get("id").asString();

        mvc.perform(get(KEYS_PATH).cookie(merchantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(idA)))
                .andExpect(jsonPath("$[*].id", not(hasItem(idB))));

        mvc.perform(get(KEYS_PATH).cookie(merchantB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(idB)))
                .andExpect(jsonPath("$[*].id", not(hasItem(idA))));
    }

    @Test
    void revokeAnotherMerchantsKeyReturns403() throws Exception {
        Cookie merchantA = approveAndLogin(uniqueEmail(), uniquePhone());
        Cookie merchantB = approveAndLogin(uniqueEmail(), uniquePhone());

        String body = postJson(KEYS_PATH, Map.of("name", "key-a"), merchantA)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String idA = json.readTree(body).get("id").asString();

        mvc.perform(delete(KEYS_PATH + "/{id}", idA).cookie(merchantB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unknownKeyReturns404() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        mvc.perform(delete(KEYS_PATH + "/{id}", UUID.randomUUID()).cookie(merchant))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void createKeyWithBlankNameReturns400() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        postJson(KEYS_PATH, Map.of("name", ""), merchant)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void keyHashIsStoredNotRawKey() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        String body = postJson(KEYS_PATH, Map.of("name", "hash-check"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String rawKey = json.readTree(body).get("rawKey").asString();
        String id = json.readTree(body).get("id").asString();

        String storedHash = jdbc.queryForObject(
                "SELECT key_hash FROM merchants.api_keys WHERE id = ?",
                String.class, UUID.fromString(id));

        // The raw key must NOT be in the database
        assert !rawKey.equals(storedHash);
        // The stored value must be a 64-char hex SHA-256 hash
        assert storedHash != null && storedHash.length() == 64;
    }

    @Test
    void revokedKeyFailsApiKeyAuth() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        String body = postJson(KEYS_PATH, Map.of("name", "revoke-me"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String rawKey = json.readTree(body).get("rawKey").asString();
        String id = json.readTree(body).get("id").asString();

        mvc.perform(delete(KEYS_PATH + "/{id}", id).cookie(merchant)).andExpect(status().isOk());

        mvc.perform(get(KEYS_PATH).header("X-Api-Key", rawKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));
    }

    @Test
    void unapprovedMerchantKeyRejected() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        Cookie merchant = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());

        String body = postJson(KEYS_PATH, Map.of("name", "downgrade-me"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String rawKey = json.readTree(body).get("rawKey").asString();

        jdbc.update("UPDATE merchants.merchant_profiles SET approval_status = 'PENDING' WHERE user_id = ?::uuid",
                userId);

        mvc.perform(get(KEYS_PATH).header("X-Api-Key", rawKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_NOT_APPROVED"));
    }
}
