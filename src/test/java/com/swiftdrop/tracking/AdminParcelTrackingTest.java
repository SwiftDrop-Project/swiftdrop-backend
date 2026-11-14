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
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin's all-parcels tracking list: what fills the index, the filters, paging and who may see it. */
class AdminParcelTrackingTest extends IntegrationTest {

    private static final String LIST = "/api/v1/admin/tracking/parcels";

    @Autowired
    DeliveryApi deliveries;

    @Autowired
    LiveUpdates liveUpdates;

    @Autowired
    TrackingRateLimiter trackingLimiter;

    private Cookie customer;
    private Cookie admin;

    @BeforeEach
    void setUp() throws Exception {
        trackingLimiter.reset();
        customer = registerCustomer(uniqueEmail(), uniquePhone());
        admin = loginAsAdmin();
    }

    @Test
    void onlyAdminsMaySeeTheList() throws Exception {
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        mvc.perform(get(LIST).cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(LIST + "/counts").cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(LIST).cookie(admin)).andExpect(status().isOk());
    }

    @Test
    void aStatusChangeAndALookupBothAddTheParcel() throws Exception {
        DeliverySnapshot changed = book("TRINCOMALEE", "MANNAR");
        DeliverySnapshot lookedUp = book("TRINCOMALEE", "VAVUNIYA");

        mvc.perform(get(LIST).param("district", "TRINCOMALEE").cookie(admin))
                .andExpect(jsonPath("$.content[*].trackingNumber", not(hasItem(changed.trackingNumber()))));

        pickUp(changed, District.TRINCOMALEE);
        mvc.perform(get("/api/v1/track/" + lookedUp.trackingNumber())).andExpect(status().isOk());

        mvc.perform(get(LIST).param("q", changed.trackingNumber()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.content[0].currentBranch").value("TRINCOMALEE"))
                .andExpect(jsonPath("$.content[0].from.district").value("TRINCOMALEE"))
                .andExpect(jsonPath("$.content[0].to.district").value("MANNAR"));
        mvc.perform(get(LIST).param("q", lookedUp.trackingNumber().toLowerCase()).cookie(admin))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("CREATED"));

        // An older event arriving later doesn't overwrite the index.
        liveUpdates.handle(event(changed, changed.version(), ParcelStatus.CREATED, null));
        mvc.perform(get(LIST).param("q", changed.trackingNumber()).cookie(admin))
                .andExpect(jsonPath("$.content[0].status").value("AT_BRANCH"));
    }

    @Test
    void filtersByStatusDistrictAndDay() throws Exception {
        DeliverySnapshot atBranch = book("POLONNARUWA", "MONARAGALA");
        DeliverySnapshot created = book("MONARAGALA", "POLONNARUWA");
        pickUp(atBranch, District.POLONNARUWA);
        mvc.perform(get("/api/v1/track/" + created.trackingNumber())).andExpect(status().isOk());

        mvc.perform(get(LIST).param("district", "POLONNARUWA").param("status", "AT_BRANCH").cookie(admin))
                .andExpect(jsonPath("$.content[*].trackingNumber", hasItem(atBranch.trackingNumber())))
                .andExpect(jsonPath("$.content[*].trackingNumber", not(hasItem(created.trackingNumber()))));
        mvc.perform(get(LIST).param("district", "MONARAGALA").cookie(admin))
                .andExpect(jsonPath("$.content[*].trackingNumber", hasItem(atBranch.trackingNumber())))
                .andExpect(jsonPath("$.content[*].trackingNumber", hasItem(created.trackingNumber())));
        mvc.perform(get(LIST).param("district", "AMPARA").cookie(admin))
                .andExpect(jsonPath("$.content[*].trackingNumber", not(hasItem(atBranch.trackingNumber()))));

        String today = LocalDate.now(ZoneId.of("Asia/Colombo")).toString();
        String yesterday = LocalDate.now(ZoneId.of("Asia/Colombo")).minusDays(1).toString();
        mvc.perform(get(LIST).param("district", "POLONNARUWA").param("from", today).param("to", today).cookie(admin))
                .andExpect(jsonPath("$.content[*].trackingNumber", hasItem(atBranch.trackingNumber())));
        mvc.perform(get(LIST).param("district", "POLONNARUWA").param("to", yesterday).cookie(admin))
                .andExpect(jsonPath("$.content[*].trackingNumber", not(hasItem(atBranch.trackingNumber()))));

        mvc.perform(get(LIST + "/counts").param("q", atBranch.trackingNumber()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byStatus.AT_BRANCH").value(1))
                .andExpect(jsonPath("$.byStatus.DELIVERED").value(0));
    }

    @Test
    void pagesAndRejectsBadInput() throws Exception {
        DeliverySnapshot second = book("KEGALLE", "KALUTARA");
        mvc.perform(get("/api/v1/track/" + second.trackingNumber())).andExpect(status().isOk());
        mvc.perform(get(LIST).param("size", "1").param("page", "0").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1));

        mvc.perform(get(LIST).param("size", "0").cookie(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(LIST).param("size", "101").cookie(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(LIST).param("page", "-1").cookie(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(LIST).param("from", "2026-11-10").param("to", "2026-11-01").cookie(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get(LIST).param("status", "LOST_IN_SPACE").cookie(admin)).andExpect(status().isBadRequest());
    }

    /** Picks the parcel up and drops it at a branch in delivery, then hands tracking the event the relay would. */
    private void pickUp(DeliverySnapshot parcel, District branch) {
        UUID courier = UUID.randomUUID();
        deliveries.changeStatus(new ChangeStatusCommand(parcel.id(), ParcelStatus.PICKED_UP, courier, "COURIER",
                null, null, null, null, null));
        deliveries.changeStatus(new ChangeStatusCommand(parcel.id(), ParcelStatus.AT_BRANCH, courier, "COURIER",
                null, null, null, null, branch));
        DeliverySnapshot now = deliveries.findById(parcel.id()).orElseThrow();
        liveUpdates.handle(event(parcel, now.version(), ParcelStatus.AT_BRANCH, branch));
    }

    private static ParcelStatusChanged event(DeliverySnapshot parcel, long version, ParcelStatus to, District branch) {
        return new ParcelStatusChanged(UUID.randomUUID(), parcel.id(), version, parcel.trackingNumber(),
                parcel.customerId(), null, ParcelStatus.CREATED, to, UUID.randomUUID(), "COURIER", null, null,
                Instant.now(), branch);
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
        // Packed measurements are required since WP3 PR6 (pricing chooses the vehicle from them).
        body.put("lengthCm", 40);
        body.put("widthCm", 30);
        body.put("heightCm", 12);
        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        return deliveries.findById(id).orElseThrow();
    }
}
