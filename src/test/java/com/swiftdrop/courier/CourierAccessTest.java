package com.swiftdrop.courier;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every courier endpoint: no login → 401, any other role (including ADMIN) → 403. */
class CourierAccessTest extends CourierApiTest {

    private static List<MockHttpServletRequestBuilder> endpoints() {
        UUID id = UUID.randomUUID();
        return List.of(
                get(BASE + "/today"),
                get(BASE + "/history"),
                get(BASE + "/{id}", id),
                post(BASE + "/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PICKED_UP\"}"));
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        for (MockHttpServletRequestBuilder request : endpoints()) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void customerGets403() throws Exception {
        expectForbidden(registerCustomer(uniqueEmail(), uniquePhone()));
    }

    @Test
    void adminGets403() throws Exception {
        expectForbidden(loginAsAdmin());
    }

    @Test
    void nonUuidParcelIdReturns400() throws Exception {
        Courier courier = registerCourier();
        mvc.perform(get(BASE + "/not-a-uuid").cookie(courier.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void expectForbidden(Cookie cookie) throws Exception {
        for (MockHttpServletRequestBuilder request : endpoints()) {
            mvc.perform(request.cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        verify(delivery, never()).changeStatus(any());
    }
}
