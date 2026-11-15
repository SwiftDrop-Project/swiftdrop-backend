// TEMPORARY - delete when WP3 implements DeliveryApi (then there is always an implementation).
package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The normal test context has no DeliveryApi at all (no fake, no mock): the app starts and courier answers 503. */
@Disabled("DeliveryApi is implemented now, so the courier endpoints never answer 503. Delete with courier/internal/fake (CLAUDE.md).")
class CourierUnavailableTest extends IntegrationTest {

    @Autowired
    IdentityApi identity;

    @Test
    void withoutADeliveryImplementationCourierEndpointsAnswer503() throws Exception {
        String email = uniqueEmail();
        identity.registerUser("Kamal Jayawardena", email, stored(uniquePhone()), PASSWORD, Role.COURIER,
                UserStatus.ACTIVE);
        Cookie courier = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        UUID parcelId = UUID.randomUUID();

        mvc.perform(get("/api/v1/courier/deliveries/today").cookie(courier))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("This isn't available yet. Please try again later."));
        mvc.perform(get("/api/v1/courier/deliveries/history").cookie(courier))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/courier/deliveries/{id}", parcelId).cookie(courier))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/courier/deliveries/{id}/status", parcelId).cookie(courier)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PICKED_UP\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
