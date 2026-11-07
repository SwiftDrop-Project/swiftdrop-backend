package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.internal.dto.CreateDeliveryRequest;
import com.swiftdrop.delivery.internal.dto.DeliveryResponse;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer bookings. Merchants send their orders through the merchants module (POST /merchant/orders). */
@RestController
@RequestMapping("/deliveries")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Deliveries")
public class DeliveryController {

    private final DeliveryApi deliveries;

    public DeliveryController(DeliveryApi deliveries) {
        this.deliveries = deliveries;
    }

    @PostMapping
    @Operation(summary = "Book a delivery (the server sets the fee and the tracking number)")
    public ResponseEntity<DeliveryResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateDeliveryRequest request) {
        DeliverySnapshot parcel = deliveries.create(request.toCommand(CurrentUser.id(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(DeliveryResponse.from(parcel));
    }
}
