// TEMPORARY - delete when WP3 and WP8 implement DeliveryApi and AssignmentApi (then there is always an implementation).
package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The normal test context has no DeliveryApi and no AssignmentApi at all (no fakes, no mocks): the app starts and
 * every courier endpoint answers 503.
 */
class CourierUnavailableTest extends IntegrationTest {

    @Autowired
    IdentityApi identity;

    @Test
    void withoutImplementationsEveryCourierEndpointAnswers503() throws Exception {
        String email = uniqueEmail();
        identity.registerUser("Kamal Jayawardena", email, stored(uniquePhone()), PASSWORD, Role.COURIER,
                UserStatus.ACTIVE);
        Cookie courier = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());

        for (MockHttpServletRequestBuilder request
                : CourierAccessTest.endpoints("/api/v1/courier/assignments", "/api/v1/courier/parcels")) {
            mvc.perform(request.cookie(courier))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("This isn't available yet. Please try again later."));
        }
    }
}
