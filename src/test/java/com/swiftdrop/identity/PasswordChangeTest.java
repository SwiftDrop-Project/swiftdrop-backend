package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordChangeTest extends IntegrationTest {

    private static final String CHANGE = "/api/v1/auth/password/change";

    @Test
    void changeKeepsThisSessionAndEndsTheOthers() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());
        Cookie laptop = tokenCookie(login(email, PASSWORD, true).andExpect(status().isOk()).andReturn());
        Cookie phone = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());

        MvcResult result = postJson(CHANGE, Map.of("currentPassword", PASSWORD, "newPassword", "changed456"), laptop)
                .andExpect(status().isNoContent())
                .andReturn();

        // Same lifetime as the session that asked (remember me).
        assertThat(setCookieHeader(result)).contains("HttpOnly", "Max-Age=604800");
        Cookie fresh = tokenCookie(result);
        mvc.perform(get("/api/v1/auth/me").cookie(fresh)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(laptop)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(phone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        login(email, "changed456", false).andExpect(status().isOk());
        login(email, PASSWORD, false).andExpect(status().isUnauthorized());
        mail.awaitMessages(email, CHANGED_SUBJECT, 1);
    }

    @Test
    void wrongCurrentPasswordIsAFieldError() throws Exception {
        Cookie cookie = registerCustomer(uniqueEmail(), uniquePhone());

        postJson(CHANGE, Map.of("currentPassword", "notMine123", "newPassword", "changed456"), cookie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.currentPassword").value("Your current password is incorrect."));
        postJson(CHANGE, Map.of("currentPassword", PASSWORD, "newPassword", "weak"), cookie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Use at least 8 characters, including a number."));
        mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void needsLogin() throws Exception {
        postJson(CHANGE, Map.of("currentPassword", PASSWORD, "newPassword", "changed456"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
