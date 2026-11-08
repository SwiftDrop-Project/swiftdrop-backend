// TEMPORARY - delete with the courier fakes.
package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.dispatch.AssignmentApi;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Only SWIFTDROP_FAKE_DELIVERY=true (the flag from before courier tasks existed): the app still starts, but with no
 * AssignmentApi there are no tasks, so the demo is not seeded and every courier endpoint answers 503.
 */
@TestPropertySource(properties = {
        "swiftdrop.dev.fake-delivery=true",
        "swiftdrop.seed.demo-users=true",
        "swiftdrop.seed.demo-password=" + OnlyFakeDeliveryTest.DEMO_PASSWORD
})
class OnlyFakeDeliveryTest extends IntegrationTest {

    static final String DEMO_PASSWORD = "Demo12345";

    @Autowired
    ObjectProvider<DeliveryApi> deliveries;

    @Autowired
    ObjectProvider<AssignmentApi> assignments;

    @Test
    void theAppStartsWithoutDemoTasksAndCourierEndpointsAnswer503() throws Exception {
        assertThat(deliveries.getIfAvailable()).as("the fake delivery is on").isNotNull();
        assertThat(assignments.getIfAvailable()).as("no dispatch").isNull();
        // the seeder needs both fakes, so not even the parcels are seeded
        assertThat(deliveries.getObject().findByTrackingNumber("SD2611060001")).isEmpty();

        Cookie courier = tokenCookie(login("courier@swiftdrop.lk", DEMO_PASSWORD, false)
                .andExpect(status().isOk()).andReturn());
        for (MockHttpServletRequestBuilder request
                : CourierAccessTest.endpoints("/api/v1/courier/assignments", "/api/v1/courier/parcels")) {
            mvc.perform(request.cookie(courier))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        }
    }
}
