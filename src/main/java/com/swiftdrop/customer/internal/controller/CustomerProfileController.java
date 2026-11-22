package com.swiftdrop.customer.internal.controller;

import com.swiftdrop.customer.internal.application.CustomerProfileService;
import com.swiftdrop.customer.internal.dto.CustomerProfileResponse;
import com.swiftdrop.customer.internal.dto.UpdatePersonalDetailsRequest;
import com.swiftdrop.customer.internal.dto.UpdatePreferencesRequest;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/customer/profile")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Customer - Profile", description = "Customer account details and delivery preferences")
public class CustomerProfileController {

    private final CustomerProfileService profileService;

    public CustomerProfileController(CustomerProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @Operation(summary = "Get current customer profile, preferences and counters")
    public CustomerProfileResponse getProfile(@AuthenticationPrincipal Jwt jwt) {
        return profileService.getProfile(CurrentUser.id(jwt));
    }

    @PatchMapping("/personal-details")
    @Operation(summary = "Update personal details (full name and mobile number)")
    public CustomerProfileResponse updatePersonalDetails(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdatePersonalDetailsRequest request) {
        return profileService.updatePersonalDetails(CurrentUser.id(jwt), request);
    }

    @PatchMapping("/preferences")
    @Operation(summary = "Update delivery preferences (default pickup address and preferred payment method)")
    public CustomerProfileResponse updatePreferences(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        return profileService.updatePreferences(CurrentUser.id(jwt), request);
    }
}
