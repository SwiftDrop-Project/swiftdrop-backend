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

    /** Every courier endpoint, with a valid body, so only the role decides the answer. */
    static List<MockHttpServletRequestBuilder> endpoints(String base, String parcels) {
        UUID id = UUID.randomUUID();
        return List.of(
                get(base + "/today"),
                get(base + "/history"),
                get(base + "/{id}", id),
                post(base + "/{id}/items/{parcelId}/outcome", id, id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"COLLECTED\"}"),
                post(base + "/{id}/handover", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelIds\":[\"" + id + "\"],\"receivedBy\":\"S. Silva\"}"),
                post(base + "/{id}/complete", id),
                get(parcels + "/{id}", id));
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        for (MockHttpServletRequestBuilder request : endpoints(BASE, PARCELS)) {
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
    void nonUuidIdsReturn400() throws Exception {
        Courier courier = registerCourier();
        mvc.perform(get(BASE + "/not-a-uuid").cookie(courier.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(PARCELS + "/not-a-uuid").cookie(courier.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(BASE + "/{id}/items/not-a-uuid/outcome", UUID.randomUUID()).cookie(courier.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"COLLECTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void expectForbidden(Cookie cookie) throws Exception {
        for (MockHttpServletRequestBuilder request : endpoints(BASE, PARCELS)) {
            mvc.perform(request.cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
        verify(assignments, never()).complete(any(), any());
    }
}
