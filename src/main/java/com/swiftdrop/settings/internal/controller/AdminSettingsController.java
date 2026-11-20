
package com.swiftdrop.settings.internal.controller;

import com.swiftdrop.settings.internal.application.CompanySettingsService;
import com.swiftdrop.settings.internal.application.DeliverySettingsService;
import com.swiftdrop.settings.internal.application.NotificationSettingsService;
import com.swiftdrop.settings.internal.application.SystemInformationService;

import com.swiftdrop.settings.internal.dto.CompanyDetailsRequest;
import com.swiftdrop.settings.internal.dto.DeliverySettingsRequest;
import com.swiftdrop.settings.internal.dto.NotificationSettingsRequest;
import com.swiftdrop.settings.internal.dto.SystemInformationResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
    name = "Admin - Settings",
    description = "Manage SwiftDrop platform settings (ADMIN only)"
)
public class AdminSettingsController {

    private final CompanySettingsService companySettingsService;
    private final DeliverySettingsService deliverySettingsService;
    private final NotificationSettingsService notificationSettingsService;
    private final SystemInformationService systemInformationService;

    public AdminSettingsController(
            CompanySettingsService companySettingsService,
            DeliverySettingsService deliverySettingsService,
            NotificationSettingsService notificationSettingsService,
            SystemInformationService systemInformationService
    ) {
        this.companySettingsService = companySettingsService;
        this.deliverySettingsService = deliverySettingsService;
        this.notificationSettingsService = notificationSettingsService;
        this.systemInformationService = systemInformationService;
    }

    // Company Details

    @GetMapping("/company")
    public ResponseEntity<CompanyDetailsRequest> getCompanyDetails() {
        return ResponseEntity.of(
                companySettingsService.getCompanyDetails()
        );
    }

    @PutMapping("/company")
    public CompanyDetailsRequest updateCompanyDetails(
            @Valid @RequestBody CompanyDetailsRequest request
    ) {
        return companySettingsService.updateCompanyDetails(request);
    }

    // Delivery Operations

    @GetMapping("/delivery")
    public ResponseEntity<DeliverySettingsRequest> getDeliverySettings() {
        return ResponseEntity.of(
                deliverySettingsService.getDeliverySettings()
        );
    }

    @PutMapping("/delivery")
    public DeliverySettingsRequest updateDeliverySettings(
            @Valid @RequestBody DeliverySettingsRequest request
    ) {
        return deliverySettingsService.updateDeliverySettings(request);
    }

    // Notification Settings

    @GetMapping("/notifications")
    public ResponseEntity<NotificationSettingsRequest> getNotificationSettings() {
        return ResponseEntity.of(
                notificationSettingsService.getNotificationSettings()
        );
    }

    @PutMapping("/notifications")
    public NotificationSettingsRequest updateNotificationSettings(
            @Valid @RequestBody NotificationSettingsRequest request
    ) {
        return notificationSettingsService.updateNotificationSettings(request);
    }

    // System Information

    @GetMapping("/system-info")
    public SystemInformationResponse getSystemInformation() {
        return systemInformationService.getSystemInformation();
    }
}
