package com.swiftdrop.dispatch.internal.controller;

import com.swiftdrop.dispatch.internal.application.DispatchService;
import com.swiftdrop.dispatch.internal.dto.AssignCourierRequest;
import com.swiftdrop.dispatch.internal.dto.ParcelAssignmentResponse;
import com.swiftdrop.identity.CourierSummary;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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

import java.util.List;
import java.util.UUID;

/** Dispatch operations for dispatchers and admins. */
@RestController
@RequestMapping("/dispatch")
@PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
@Tag(name = "Dispatch", description = "Active courier lookup and parcel assignment (DISPATCHER and ADMIN)")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @GetMapping("/couriers")
    @Operation(summary = "List active couriers eligible for parcel assignment (optionally filtered by branch)")
    public List<CourierSummary> listActiveCouriers(@RequestParam(required = false) District branch) {
        return dispatchService.listActiveCouriers(branch);
    }

    @PostMapping(path = "/parcels/{parcelId}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Validate courier eligibility and assign courier to a parcel")
    public ParcelAssignmentResponse assignCourier(@PathVariable UUID parcelId,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody AssignCourierRequest request) {
        return dispatchService.assignCourier(parcelId, request.courierId(), CurrentUser.id(jwt));
    }
}

