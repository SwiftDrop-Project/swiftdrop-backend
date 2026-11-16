package com.swiftdrop.courier.internal.controller;

import com.swiftdrop.courier.internal.application.CourierDeliveryService;
import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.courier.internal.dto.CourierDeliveryDetailResponse;
import com.swiftdrop.courier.internal.dto.CourierDeliveryResponse;
import com.swiftdrop.courier.internal.dto.CourierTodayResponse;
import com.swiftdrop.courier.internal.dto.StatusChangeRequest;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The logged-in courier's parcels. COURIER only; other couriers' parcels answer 404. */
@RestController
@RequestMapping("/courier/deliveries")
@PreAuthorize("hasRole('COURIER')")
@Tag(name = "Courier - deliveries", description = "Today's parcels, status updates and history (COURIER only)")
public class CourierDeliveryController {

    private final CourierDeliveryService courierDeliveries;

    public CourierDeliveryController(CourierDeliveryService courierDeliveries) {
        this.courierDeliveries = courierDeliveries;
    }

    @GetMapping("/today")
    @Operation(summary = "Open parcels assigned to me, plus the ones I finished today (Sri Lankan time)")
    public CourierTodayResponse today(@AuthenticationPrincipal Jwt jwt) {
        CourierToday today = courierDeliveries.today(CurrentUser.id(jwt));
        return new CourierTodayResponse(today.date(), today.toDo(), today.done(),
                today.parcels().stream().map(item -> CourierDeliveryResponse.from(item.parcel(), item.task())).toList());
    }

    @GetMapping("/history")
    @Operation(summary = "Parcels I delivered or marked failed, newest first")
    public PageResponse<CourierDeliveryResponse> history(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(required = false) String trackingNumber) {
        return PageResponse.from(courierDeliveries.history(CurrentUser.id(jwt), page, size, trackingNumber),
                item -> CourierDeliveryResponse.from(item.parcel(), item.task()));
    }

    @GetMapping("/{parcelId}")
    @Operation(summary = "One of my parcels with its history")
    public CourierDeliveryDetailResponse detail(@PathVariable UUID parcelId, @AuthenticationPrincipal Jwt jwt) {
        return toResponse(courierDeliveries.detail(CurrentUser.id(jwt), parcelId));
    }

    @PostMapping("/{parcelId}/status")
    @Operation(summary = "Move one of my parcels to its next status (with a recipient name or a failure reason)")
    public CourierDeliveryDetailResponse changeStatus(@PathVariable UUID parcelId,
                                                      @Valid @RequestBody StatusChangeRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return toResponse(courierDeliveries.changeStatus(CurrentUser.id(jwt), parcelId, request));
    }

    private static CourierDeliveryDetailResponse toResponse(ParcelDetail detail) {
        return CourierDeliveryDetailResponse.from(detail.parcel(), detail.history(), detail.task());
    }
}
