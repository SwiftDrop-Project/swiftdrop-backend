package com.swiftdrop.identity.internal.controller;

import com.swiftdrop.identity.internal.application.StaffManagementService;
import com.swiftdrop.identity.internal.dto.CreateDispatcherRequest;
import com.swiftdrop.identity.internal.dto.DispatcherDetailResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Dispatcher staff management. ADMIN only. */
@RestController
@RequestMapping("/admin/dispatchers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - dispatchers", description = "Create and view dispatcher staff accounts (ADMIN only)")
public class AdminDispatcherController {

    private final StaffManagementService staffService;

    public AdminDispatcherController(StaffManagementService staffService) {
        this.staffService = staffService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a dispatcher account with an initial password (must change on first login)")
    public ResponseEntity<DispatcherDetailResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody CreateDispatcherRequest request) {
        DispatcherDetailResponse created = staffService.createDispatcher(request, CurrentUser.id(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(summary = "List dispatcher accounts (paginated, filterable by search query and branch)")
    public PageResponse<DispatcherDetailResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) District branch,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(staffService.listDispatchers(
                q, branch, PageRequest.of(Math.max(0, page), Math.max(1, size))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "View dispatcher details")
    public DispatcherDetailResponse get(@PathVariable UUID id) {
        return staffService.getDispatcher(id);
    }
}

