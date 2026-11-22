package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDispatcherManagementTest extends IntegrationTest {

    private static final String DISPATCHERS = "/api/v1/admin/dispatchers";

    private static Map<String, Object> dispatcherBody(String fullName, String email, String phone,
                                                      String branch, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("phone", phone);
        body.put("branch", branch);
        body.put("password", password);
        return body;
    }

    @Test
    void adminCreatesListsFiltersAndViewsDispatchers() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String phone = uniquePhone();

        MvcResult created = postJson(DISPATCHERS,
                dispatcherBody("Sanduni Wijeratne", email.toUpperCase(), phone, "KANDY", "Dispatch123"), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Sanduni Wijeratne"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(stored(phone)))
                .andExpect(jsonPath("$.branch").value("KANDY"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get(DISPATCHERS).param("branch", "KANDY").param("q", "Sanduni").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));

        mvc.perform(get(DISPATCHERS).param("branch", "COLOMBO").param("q", "Sanduni").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id))));

        mvc.perform(get(DISPATCHERS + "/{id}", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.branch").value("KANDY"));

        mvc.perform(get(DISPATCHERS + "/{id}", UUID.randomUUID()).cookie(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void dispatcherRoleCannotAccessAdminEndpointsEvenAfterPasswordChange() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String phone = uniquePhone();

        postJson(DISPATCHERS, dispatcherBody("Tharindu Silva", email, phone, "COLOMBO", "InitPass123"), admin)
                .andExpect(status().isCreated());

        Cookie initialSession = tokenCookie(login(email, "InitPass123", false).andExpect(status().isOk()).andReturn());
        Cookie dispatcherSession = tokenCookie(postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", "InitPass123", "newPassword", "MyDispatch456"), initialSession)
                .andExpect(status().isNoContent())
                .andReturn());

        // Dispatcher can access /dispatch/couriers
        mvc.perform(get("/api/v1/dispatch/couriers").cookie(dispatcherSession))
                .andExpect(status().isOk());

        // Dispatcher is strictly forbidden from all /admin/** endpoints (including merchant approvals and staff management)
        mvc.perform(get("/api/v1/admin/merchants").cookie(dispatcherSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/couriers").cookie(dispatcherSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(DISPATCHERS).cookie(dispatcherSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}

