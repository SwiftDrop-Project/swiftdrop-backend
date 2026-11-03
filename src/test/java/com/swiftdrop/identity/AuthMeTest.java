package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthMeTest extends IntegrationTest {

    @Test
    void withoutCookieReturns401Json() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void withInvalidCookieReturns401() throws Exception {
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(COOKIE, "garbage")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void withCookieReturnsCurrentUser() throws Exception {
        String email = uniqueEmail();
        Cookie cookie = registerCustomer(email, uniquePhone());

        mvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.merchant").isEmpty());
    }

    @Test
    void approvedMerchantSeesMerchantBlock() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        Cookie cookie = tokenCookie(login(email, PASSWORD, false).andReturn());

        mvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.merchant.businessName").value("Lanka Crafts"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("APPROVED"));
    }
}
