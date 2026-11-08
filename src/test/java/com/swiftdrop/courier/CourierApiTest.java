package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the courier API tests: delivery is a Mockito mock (nothing implements DeliveryApi yet), so these tests
 * check only the courier module: roles, ownership, request rules and mapping. All subclasses share one Spring context.
 */
abstract class CourierApiTest extends IntegrationTest {

    protected static final String BASE = "/api/v1/courier/deliveries";

    private static final AtomicInteger TRACKING_SEQUENCE = new AtomicInteger(1);

    @MockitoBean
    protected DeliveryApi delivery;

    @Autowired
    protected IdentityApi identity;

    protected record Courier(UUID id, Cookie cookie) {
    }

    /** An ACTIVE courier (created through IdentityApi, as an admin would) and its login cookie. */
    protected Courier registerCourier() throws Exception {
        String email = uniqueEmail();
        UserSummary user = identity.registerUser("Kamal Jayawardena", email, stored(uniquePhone()), PASSWORD,
                Role.COURIER, UserStatus.ACTIVE);
        Cookie cookie = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        return new Courier(user.id(), cookie);
    }

    /** A parcel assigned to {@code courierId}. The only place that builds a DeliverySnapshot. */
    protected static DeliverySnapshot parcel(UUID courierId, ParcelStatus status, Instant assignedAt,
                                             Instant updatedAt) {
        return new DeliverySnapshot(UUID.randomUUID(),
                String.format("SD2611%06d", TRACKING_SEQUENCE.getAndIncrement()), status, null, UUID.randomUUID(),
                new DeliveryContact("Lanka Crafts", "+94760000101", "12 High Level Road", "Nugegoda", District.COLOMBO),
                new DeliveryContact("Nimal Perera", "+94760000201", "45 Peradeniya Road", "Kandy", District.KANDY),
                new BigDecimal("1.20"), new BigDecimal("2450.00"), "Call before you arrive.", courierId, assignedAt,
                assignedAt.minus(1, ChronoUnit.HOURS), updatedAt,
                status == ParcelStatus.DELIVERED ? "Nimal Perera" : null, false,
                status == ParcelStatus.FAILED ? FailureReason.RECIPIENT_UNAVAILABLE : null,
                "ORDER-1", new BigDecimal("450.00"), "LKR", 3,
                status == ParcelStatus.AT_BRANCH ? District.COLOMBO : null);
    }

    protected static DeliverySnapshot parcel(UUID courierId, ParcelStatus status) {
        Instant now = Instant.now();
        return parcel(courierId, status, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
    }

    /** Makes delivery return this parcel (with an empty history). */
    protected DeliverySnapshot given(DeliverySnapshot parcel) {
        when(delivery.findById(parcel.id())).thenReturn(Optional.of(parcel));
        when(delivery.history(parcel.id())).thenReturn(List.of());
        return parcel;
    }

    protected ResultActions postStatus(UUID parcelId, Map<String, ?> body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/status", parcelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .cookie(cookie));
    }

    protected ResultActions postStatusRaw(UUID parcelId, String body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/status", parcelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(cookie));
    }
}
