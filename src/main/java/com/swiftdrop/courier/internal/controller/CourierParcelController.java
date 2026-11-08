package com.swiftdrop.courier.internal.controller;

import com.swiftdrop.courier.internal.application.CourierParcelService;
import com.swiftdrop.courier.internal.dto.CourierParcelResponse;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** A parcel from the logged-in courier's tasks. COURIER only; parcels that were never in their tasks answer 404. */
@RestController
@RequestMapping("/courier/parcels")
@PreAuthorize("hasRole('COURIER')")
@Tag(name = "Courier - parcels", description = "One parcel from my tasks with its status history (COURIER only)")
public class CourierParcelController {

    private final CourierParcelService courierParcels;

    public CourierParcelController(CourierParcelService courierParcels) {
        this.courierParcels = courierParcels;
    }

    @GetMapping("/{parcelId}")
    @Operation(summary = "One parcel from my tasks, with its status history and my items for it")
    public CourierParcelResponse detail(@PathVariable UUID parcelId, @AuthenticationPrincipal Jwt jwt) {
        return CourierParcelResponse.from(courierParcels.detail(CurrentUser.id(jwt), parcelId));
    }
}
