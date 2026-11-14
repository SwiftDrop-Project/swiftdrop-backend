package com.swiftdrop.identity.internal.controller;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.internal.application.AdminUserService;
import com.swiftdrop.identity.internal.application.AdminUserService.UserSearch;
import com.swiftdrop.identity.internal.dto.AdminUserDetailResponse;
import com.swiftdrop.identity.internal.dto.AdminUserResponse;
import com.swiftdrop.identity.internal.dto.UpdateUserRequest;
import com.swiftdrop.identity.internal.dto.UpdateUserStatusRequest;
import com.swiftdrop.identity.internal.dto.UserSort;
import com.swiftdrop.identity.internal.dto.UserStatsResponse;
import com.swiftdrop.identity.internal.dto.UserStatusFilter;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** User management for every account. ADMIN only. */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - users", description = "Find, view, edit, block and unblock any account (ADMIN only)")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List accounts (paginated): search by name, email, phone or id; filter by role, status "
            + "and registration date; sort newest, oldest or by name")
    public PageResponse<AdminUserResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatusFilter status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredTo,
            @RequestParam(defaultValue = "NEWEST") UserSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserSearch search = new UserSearch(q, role, status, registeredFrom, registeredTo, sort);
        return PageResponse.from(userService.list(search, page, size));
    }

    @GetMapping("/stats")
    @Operation(summary = "Summary figures: total, active, blocked, pending and new accounts (last 30 days)")
    public UserStatsResponse stats() {
        return userService.stats();
    }

    @GetMapping("/{id}")
    @Operation(summary = "View one account in full (merchant business details included for merchants)")
    public AdminUserDetailResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Edit an account's name, email and phone (a new email ends the user's sessions)")
    public AdminUserDetailResponse update(@PathVariable UUID id,
                                          @AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request, CurrentUser.id(jwt));
    }

    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Block an active account (sessions end at once) or unblock a blocked one")
    public AdminUserDetailResponse changeStatus(@PathVariable UUID id,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody UpdateUserStatusRequest request) {
        return userService.changeStatus(id, request.status(), CurrentUser.id(jwt));
    }
}
