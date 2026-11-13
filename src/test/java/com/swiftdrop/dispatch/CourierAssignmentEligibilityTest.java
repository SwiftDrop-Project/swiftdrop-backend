package com.swiftdrop.dispatch;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourierAssignmentEligibilityTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdentityApi identity;

    @Autowired
    DeliveryApi deliveries;

    private String createCourier(Cookie admin, String branch, String vehicleType) throws Exception {
        String body = postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Courier " + UUID.randomUUID().toString().substring(0, 6),
                "email", uniqueEmail(),
                "phone", uniquePhone(),
                "branch", branch,
                "vehicleType", vehicleType,
                "password", "CourierInit123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private Cookie createActiveDispatcher(Cookie admin) throws Exception {
        String email = uniqueEmail();
        postJson("/api/v1/admin/dispatchers", Map.of(
                "fullName", "Dispatcher " + UUID.randomUUID().toString().substring(0, 6),
                "email", email,
                "phone", uniquePhone(),
                "branch", "COLOMBO",
                "password", "DispInit123"), admin)
                .andExpect(status().isCreated());
        Cookie first = tokenCookie(login(email, "DispInit123", false).andExpect(status().isOk()).andReturn());
        return tokenCookie(postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", "DispInit123", "newPassword", "DispReady456"), first)
                .andExpect(status().isNoContent())
                .andReturn());
    }

    private String bookCustomerParcel(Cookie customer) throws Exception {
        Map<String, Object> pickup = Map.of(
                "name", "Ayesha Fernando",
                "phone", "0771234567",
                "addressLine", "10 Galle Road",
                "city", "Colombo 03",
                "district", "COLOMBO");
        Map<String, Object> recipient = Map.of(
                "name", "Kamal Perera",
                "phone", "0719876543",
                "addressLine", "25 Kandy Road",
                "city", "Kandy",
                "district", "KANDY");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", pickup);
        body.put("recipient", recipient);
        body.put("weightKg", new BigDecimal("1.50"));

        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }

    @Test
    void blockedAndRemovedCouriersAreRejectedOnDirectUuidAssignmentWhilePreservingParcelHistory() throws Exception {
        Cookie admin = loginAsAdmin();
        Cookie dispatcher = createActiveDispatcher(admin);
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        String activeCourierId = createCourier(admin, "COLOMBO", "MOTORBIKE");
        String secondActiveCourierId = createCourier(admin, "COLOMBO", "THREE_WHEELER");
        String blockedCourierId = createCourier(admin, "COLOMBO", "VAN");
        String removedCourierId = createCourier(admin, "COLOMBO", "LORRY");

        mvc.perform(post("/api/v1/admin/couriers/{id}/block", blockedCourierId).cookie(admin))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/couriers/{id}", removedCourierId).cookie(admin))
                .andExpect(status().isOk());

        // Active courier list excludes blocked and removed couriers
        mvc.perform(get("/api/v1/dispatch/couriers").param("branch", "COLOMBO").cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(activeCourierId)))
                .andExpect(jsonPath("$[*].id", not(hasItem(blockedCourierId))))
                .andExpect(jsonPath("$[*].id", not(hasItem(removedCourierId))));

        // Direct IdentityApi boundary check also enforces COURIER_NOT_ASSIGNABLE
        assertThat(identity.requireAssignableCourier(UUID.fromString(activeCourierId)).id())
                .isEqualTo(UUID.fromString(activeCourierId));
        assertThatThrownBy(() -> identity.requireAssignableCourier(UUID.fromString(blockedCourierId)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.COURIER_NOT_ASSIGNABLE));
        assertThatThrownBy(() -> identity.requireAssignableCourier(UUID.fromString(removedCourierId)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.COURIER_NOT_ASSIGNABLE));

        String parcelId = bookCustomerParcel(customer);

        // Submitting a blocked courier UUID directly is rejected with 409 COURIER_NOT_ASSIGNABLE
        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign",
                Map.of("courierId", blockedCourierId), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COURIER_NOT_ASSIGNABLE"));

        // Submitting a removed courier UUID directly is rejected with 409 COURIER_NOT_ASSIGNABLE
        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign",
                Map.of("courierId", removedCourierId), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COURIER_NOT_ASSIGNABLE"));

        // Submitting a non-courier UUID returns 404 NOT_FOUND
        UUID customerId = jdbc.queryForObject(
                "SELECT customer_id FROM delivery.parcels WHERE id = ?::uuid", UUID.class, parcelId);
        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign",
                Map.of("courierId", customerId.toString()), dispatcher)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Submitting an unknown parcel UUID with an active courier returns 404 NOT_FOUND
        postJson("/api/v1/dispatch/parcels/" + UUID.randomUUID() + "/assign",
                Map.of("courierId", activeCourierId), dispatcher)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Submitting an active courier on an open parcel passes eligibility and parcel validation and creates the
        // parcel's next WP8 task (a pickup for a new parcel), without touching delivery's deprecated workflow.
        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign",
                Map.of("courierId", activeCourierId), dispatcher)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentType").value("PICKUP"));

        // Record real courier delivery activity + existing assignment, then block & soft-remove that courier
        UUID courierUuid = UUID.fromString(activeCourierId);
        UUID parcelUuid = UUID.fromString(parcelId);
        jdbc.update("UPDATE delivery.parcels SET assigned_courier_id = ?::uuid, assigned_at = now() WHERE id = ?::uuid",
                activeCourierId, parcelId);
        deliveries.changeStatus(new ChangeStatusCommand(
                parcelUuid, ParcelStatus.PICKED_UP, courierUuid, "COURIER", "Collected from sender", null, null, null));
        deliveries.changeStatus(new ChangeStatusCommand(
                parcelUuid, ParcelStatus.AT_BRANCH, courierUuid, "COURIER", null, null, null, null, District.COLOMBO));
        deliveries.changeStatus(new ChangeStatusCommand(
                parcelUuid, ParcelStatus.OUT_FOR_DELIVERY, courierUuid, "COURIER", null, null, null, null));
        deliveries.changeStatus(new ChangeStatusCommand(
                parcelUuid, ParcelStatus.DELIVERED, courierUuid, "COURIER", null, null, "Kamal Perera", null));

        // Finished (DELIVERED) parcel is rejected as 409 PARCEL_NOT_ASSIGNABLE even for an active courier
        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign",
                Map.of("courierId", secondActiveCourierId), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARCEL_NOT_ASSIGNABLE"));

        // Later blocking and soft-removing the courier preserves existing parcel assignment and status history
        mvc.perform(post("/api/v1/admin/couriers/{id}/block", activeCourierId).cookie(admin))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/couriers/{id}", activeCourierId).cookie(admin))
                .andExpect(status().isOk());

        UUID storedCourierId = jdbc.queryForObject(
                "SELECT assigned_courier_id FROM delivery.parcels WHERE id = ?::uuid", UUID.class, parcelId);
        Long historyCount = jdbc.queryForObject(
                "SELECT count(*) FROM delivery.parcel_status_history WHERE parcel_id = ?::uuid", Long.class, parcelId);
        String userStatus = jdbc.queryForObject(
                "SELECT status FROM identity.users WHERE id = ?::uuid", String.class, activeCourierId);
        assertThat(storedCourierId).isEqualTo(courierUuid);
        assertThat(historyCount).isEqualTo(5L);
        assertThat(deliveries.history(parcelUuid)).hasSize(5);
        assertThat(userStatus).isEqualTo("REMOVED");
    }
}

