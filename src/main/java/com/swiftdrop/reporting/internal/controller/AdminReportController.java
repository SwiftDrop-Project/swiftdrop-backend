package com.swiftdrop.reporting.internal.controller;

import com.swiftdrop.reporting.internal.application.AdminOverviewService;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports for the admin portal. ADMIN only. */
@RestController
@RequestMapping("/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - reports", description = "Figures and reports for the admin portal (ADMIN only)")
public class AdminReportController {

    private final AdminOverviewService overviewService;

    public AdminReportController(AdminOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    @Operation(summary = "Admin home page figures: accounts per role, parcels today and in the last 7 days, newest accounts")
    public AdminOverviewResponse overview() {
        return overviewService.overview();
    }
}
