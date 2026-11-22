package com.swiftdrop.identity.internal.controller;

import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.application.StaffManagementService;
import com.swiftdrop.identity.internal.dto.AdminResetPasswordRequest;
import com.swiftdrop.identity.internal.dto.CourierDetailResponse;
import com.swiftdrop.identity.internal.dto.CreateCourierRequest;
import com.swiftdrop.identity.internal.dto.UpdateCourierRequest;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Courier staff management. ADMIN only. */
@RestController
@RequestMapping("/admin/couriers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - couriers", description = "Create and manage courier staff accounts (ADMIN only)")
public class AdminCourierController {

    private final StaffManagementService staffService;

    public AdminCourierController(StaffManagementService staffService) {
        this.staffService = staffService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a courier account with an initial password (must change on first login)")
    public ResponseEntity<CourierDetailResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody CreateCourierRequest request) {
        CourierDetailResponse created = staffService.createCourier(request, CurrentUser.id(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(summary = "List courier accounts (paginated, filterable by search query, branch and status)")
    public PageResponse<CourierDetailResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) District branch,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(staffService.listCouriers(
                q, branch, status, PageRequest.of(Math.max(0, page), Math.max(1, size))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "View courier details")
    public CourierDetailResponse get(@PathVariable UUID id) {
        return staffService.getCourier(id);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update courier details (including login email)")
    public CourierDetailResponse update(@PathVariable UUID id,
                                        @AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody UpdateCourierRequest request) {
        return staffService.updateCourier(id, request, CurrentUser.id(jwt));
    }

    @PostMapping("/{id}/block")
    @Operation(summary = "Block a courier (status becomes DISABLED and active sessions end)")
    public CourierDetailResponse block(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return staffService.blockCourier(id, CurrentUser.id(jwt));
    }

    @PostMapping("/{id}/unblock")
    @Operation(summary = "Unblock a disabled courier (status becomes ACTIVE)")
    public CourierDetailResponse unblock(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return staffService.unblockCourier(id, CurrentUser.id(jwt));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-remove a courier (status becomes REMOVED while preserving parcel history)")
    public CourierDetailResponse remove(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return staffService.removeCourier(id, CurrentUser.id(jwt));
    }

    @PostMapping(path = "/{id}/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reset a courier's password to an admin-entered temporary password")
    public CourierDetailResponse resetPassword(@PathVariable UUID id,
                                               @AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody AdminResetPasswordRequest request) {
        return staffService.resetCourierPassword(id, request, CurrentUser.id(jwt));
    }
}

