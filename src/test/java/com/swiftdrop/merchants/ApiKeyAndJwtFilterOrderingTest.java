package com.swiftdrop.merchants;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyAndJwtFilterOrderingTest extends IntegrationTest {

    private static final String KEYS_PATH = "/api/v1/merchant/api-keys";

    @Autowired
    JdbcTemplate jdbc;

    private record ProvisionedMerchant(String userId, Cookie cookie, String keyId, String rawKey) {
    }

    private ProvisionedMerchant createMerchantWithKey(String keyName) throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        Cookie cookie = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        String body = postJson(KEYS_PATH, Map.of("name", keyName), cookie)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String keyId = json.readTree(body).get("id").asString();
        String rawKey = json.readTree(body).get("rawKey").asString();
        return new ProvisionedMerchant(userId, cookie, keyId, rawKey);
    }

    @Test
    void validApiKeyAuthenticatesWithoutCookie() throws Exception {
        ProvisionedMerchant merchant = createMerchantWithKey("no-cookie-key");

        mvc.perform(get(KEYS_PATH).header("X-Api-Key", merchant.rawKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(merchant.keyId())));
    }

    @Test
    void validApiKeyTakesPrecedenceOverValidAndMalformedCookies() throws Exception {
        ProvisionedMerchant merchantA = createMerchantWithKey("merchant-a-key");
        ProvisionedMerchant merchantB = createMerchantWithKey("merchant-b-key");
        Cookie customerCookie = registerCustomer(uniqueEmail(), uniquePhone());
        Cookie brokenCookie = new Cookie(COOKIE, "expired-or-malformed.jwt.value");

        // Explicit API key for Merchant A wins even when Merchant B's valid cookie is also attached.
        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", merchantA.rawKey())
                        .cookie(merchantB.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(merchantA.keyId())))
                .andExpect(jsonPath("$[*].id", not(hasItem(merchantB.keyId()))));

        // Explicit API key for Merchant A wins over a non-merchant session cookie.
        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", merchantA.rawKey())
                        .cookie(customerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(merchantA.keyId())));

        // Explicit API key for Merchant A is unaffected by an expired/corrupt SD_TOKEN cookie.
        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", merchantA.rawKey())
                        .cookie(brokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(merchantA.keyId())));
    }

    @Test
    void invalidOrRevokedApiKeyNeverFallsBackToValidCookie() throws Exception {
        ProvisionedMerchant merchant = createMerchantWithKey("revocable-key");

        // Unknown API key with no cookie -> 401 API_KEY_REVOKED
        mvc.perform(get(KEYS_PATH).header("X-Api-Key", "sd_invalid_key_that_does_not_exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));

        // Unknown API key WITH valid merchant cookie -> still 401 API_KEY_REVOKED (never falls back to cookie)
        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", "sd_invalid_key_that_does_not_exist")
                        .cookie(merchant.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));

        // Blank API key WITH valid merchant cookie -> 401 API_KEY_REVOKED
        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", "   ")
                        .cookie(merchant.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));

        // Revoke the key, then test with and without valid cookie
        mvc.perform(delete(KEYS_PATH + "/{id}", merchant.keyId()).cookie(merchant.cookie()))
                .andExpect(status().isOk());

        mvc.perform(get(KEYS_PATH).header("X-Api-Key", merchant.rawKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));

        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", merchant.rawKey())
                        .cookie(merchant.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));
    }

    @Test
    void unapprovedMerchantApiKeyIsRejectedEvenWhenCookieIsPresent() throws Exception {
        ProvisionedMerchant merchant = createMerchantWithKey("pending-check");
        Cookie otherValidMerchant = createMerchantWithKey("other-valid").cookie();

        jdbc.update("UPDATE merchants.merchant_profiles SET approval_status = 'PENDING' WHERE user_id = ?::uuid",
                merchant.userId());

        mvc.perform(get(KEYS_PATH)
                        .header("X-Api-Key", merchant.rawKey())
                        .cookie(otherValidMerchant))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_NOT_APPROVED"));
    }
}

