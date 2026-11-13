package com.swiftdrop.tracking;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.tracking.internal.application.LiveUpdates;
import com.swiftdrop.tracking.internal.application.TrackingRateLimiter;
import com.swiftdrop.tracking.internal.dto.LiveEvent;
import com.swiftdrop.tracking.internal.dto.TrackingUpdate;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public tracking lookup, the dispatchers' recent changes, the WebSocket handshake rules, and the live updates a
 * parcel event turns into (checked on the broker channel the STOMP broker reads from).
 */
class TrackingApiTest extends IntegrationTest {

    @Autowired
    DeliveryApi deliveries;

    @Autowired
    LiveUpdates liveUpdates;

    @Autowired
    TrackingRateLimiter trackingLimiter;

    @Autowired
    @Qualifier("brokerChannel")
    SubscribableChannel brokerChannel;

    private final List<Message<?>> pushed = new CopyOnWriteArrayList<>();
    private final MessageHandler capture = pushed::add;

    private Cookie customer;

    @BeforeEach
    void setUp() throws Exception {
        trackingLimiter.reset();
        brokerChannel.subscribe(capture);
        customer = registerCustomer(uniqueEmail(), uniquePhone());
    }

    @AfterEach
    void tearDown() {
        brokerChannel.unsubscribe(capture);
    }

    // ---- Public lookup ----

    @Test
    void anyoneCanTrackAParcelWithoutPersonalData() throws Exception {
        DeliverySnapshot parcel = book("COLOMBO", "KANDY");
        String spaced = parcel.trackingNumber().substring(0, 2) + " " + parcel.trackingNumber().substring(2, 6) + " "
                + parcel.trackingNumber().substring(6).toLowerCase();
        UUID courier = UUID.randomUUID();
        deliveries.changeStatus(new ChangeStatusCommand(parcel.id(), ParcelStatus.PICKED_UP, courier, "COURIER",
                "Gate code 1234", null, null, null));
        deliveries.changeStatus(new ChangeStatusCommand(parcel.id(), ParcelStatus.AT_BRANCH, courier, "COURIER",
                null, null, null, null, District.COLOMBO));

        String body = mvc.perform(get("/api/v1/track/" + spaced))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value(parcel.trackingNumber()))
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.currentBranch").value("COLOMBO"))
                .andExpect(jsonPath("$.from.city").value("Colombo 03"))
                .andExpect(jsonPath("$.to.district").value("KANDY"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].status").value("CREATED"))
                .andExpect(jsonPath("$.history[2].branch").value("COLOMBO"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Ayesha", "Kamal", "0771234567", "771234567", "Galle Road", "Kandy Road",
                "Gate code", "COURIER", parcel.customerId().toString(), "fee", "cod");
    }

    @Test
    void unknownOrMalformedNumbersAreNotFound() throws Exception {
        mvc.perform(get("/api/v1/track/SD2611999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/track/not-a-number"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupsAreLimitedPerClient() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/v1/track/SD2611999999")).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/track/SD2611999999"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    // ---- WebSocket handshakes ----

    @Test
    void staffHandshakeNeedsALoginButThePublicOneDoesNot() throws Exception {
        mvc.perform(get("/api/v1/ws")).andExpect(status().isUnauthorized());
        // Without the upgrade headers the handshake fails, but it reaches the WebSocket handler: not 401 or 403.
        int publicStatus = mvc.perform(get("/api/v1/ws/public")).andReturn().getResponse().getStatus();
        assertThat(publicStatus).isNotIn(401, 403);
        int staffStatus = mvc.perform(get("/api/v1/ws").cookie(customer)).andReturn().getResponse().getStatus();
        assertThat(staffStatus).isNotIn(401, 403);
    }

    // ---- Live updates ----

    @Test
    void aParcelEventIsPushedToItsTopicAndTheLiveBoard() throws Exception {
        DeliverySnapshot parcel = book("GALLE", "MATARA");
        ParcelStatusChanged event = new ParcelStatusChanged(UUID.randomUUID(), parcel.id(), parcel.version() + 1,
                parcel.trackingNumber(), parcel.customerId(), null, ParcelStatus.CREATED, ParcelStatus.AT_BRANCH,
                UUID.randomUUID(), "COURIER", "Left with the guard", null, Instant.now(), District.GALLE);

        liveUpdates.handle(event);
        liveUpdates.handle(event); // a repeat is dropped

        List<Message<?>> forParcel = sentTo("/topic/parcels/" + parcel.trackingNumber());
        assertThat(forParcel).hasSize(1);
        TrackingUpdate update = json.readValue((byte[]) forParcel.getFirst().getPayload(), TrackingUpdate.class);
        assertThat(update.status()).isEqualTo(ParcelStatus.AT_BRANCH);
        assertThat(update.branch()).isEqualTo(District.GALLE);
        assertThat(update.version()).isEqualTo(parcel.version() + 1);

        List<Message<?>> live = sentTo("/topic/live");
        assertThat(live).hasSize(1);
        String liveJson = new String((byte[]) live.getFirst().getPayload(), StandardCharsets.UTF_8);
        LiveEvent liveEvent = json.readValue(liveJson, LiveEvent.class);
        assertThat(liveEvent.origin()).isEqualTo(District.GALLE);
        assertThat(liveEvent.destination()).isEqualTo(District.MATARA);
        assertThat(liveJson).doesNotContain("Left with the guard", "Ayesha", "0771234567");
        assertThat(new String((byte[]) forParcel.getFirst().getPayload(), StandardCharsets.UTF_8))
                .doesNotContain("Left with the guard", "COURIER");

        // An older version arriving late is dropped too.
        liveUpdates.handle(new ParcelStatusChanged(UUID.randomUUID(), parcel.id(), parcel.version(),
                parcel.trackingNumber(), parcel.customerId(), null, null, ParcelStatus.CREATED, null, "CUSTOMER",
                null, null, Instant.now(), null));
        assertThat(sentTo("/topic/parcels/" + parcel.trackingNumber())).hasSize(1);
    }

    @Test
    void recentChangesAreForDispatchersAndFilterByBranch() throws Exception {
        DeliverySnapshot parcel = book("BADULLA", "JAFFNA");
        liveUpdates.handle(new ParcelStatusChanged(UUID.randomUUID(), parcel.id(), parcel.version() + 1,
                parcel.trackingNumber(), parcel.customerId(), null, ParcelStatus.CREATED, ParcelStatus.PICKED_UP,
                UUID.randomUUID(), "COURIER", null, null, Instant.now(), null));

        mvc.perform(get("/api/v1/tracking/live/recent")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracking/live/recent").cookie(customer)).andExpect(status().isForbidden());

        Cookie admin = loginAsAdmin();
        mvc.perform(get("/api/v1/tracking/live/recent").param("branch", "JAFFNA").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].trackingNumber", hasItem(parcel.trackingNumber())));
        mvc.perform(get("/api/v1/tracking/live/recent").param("branch", "AMPARA").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].trackingNumber", not(hasItem(parcel.trackingNumber()))));
    }

    private List<Message<?>> sentTo(String destination) {
        return pushed.stream()
                .filter(message -> destination.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders())))
                .toList();
    }

    private DeliverySnapshot book(String pickupDistrict, String recipientDistrict) throws Exception {
        Map<String, Object> pickup = new HashMap<>(Map.of(
                "name", "Ayesha Fernando",
                "phone", "0771234567",
                "addressLine", "10 Galle Road",
                "city", "Colombo 03",
                "district", pickupDistrict));
        Map<String, Object> recipient = Map.of(
                "name", "Kamal Perera",
                "phone", "0719876543",
                "addressLine", "25 Kandy Road",
                "city", "City",
                "district", recipientDistrict);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", pickup);
        body.put("recipient", recipient);
        body.put("weightKg", new BigDecimal("1.50"));
        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        return deliveries.findById(id).orElseThrow();
    }
}
